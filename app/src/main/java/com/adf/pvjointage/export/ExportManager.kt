package com.adf.pvjointage.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.adf.pvjointage.R
import com.adf.pvjointage.data.BrideCatalog
import com.adf.pvjointage.data.InspectionResult
import com.adf.pvjointage.data.ItemSchema
import com.adf.pvjointage.data.Photo
import com.adf.pvjointage.data.PvHeader
import com.adf.pvjointage.data.Repository
import com.adf.pvjointage.data.itemDisplayLabel
import com.adf.pvjointage.data.toInspectionResult
import com.adf.pvjointage.model.ConformiteCalculator
import com.adf.pvjointage.model.Conformite
import com.adf.pvjointage.model.Etat
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream

/**
 * Génère l'export PDF du PV (l'export Excel "natif" est géré séparément par
 * [com.adf.pvjointage.data.ExcelNativeExporter], appelé depuis [Repository.exportNativeExcel]).
 * - PDF : un fichier par ITEM — 1ère page = affiche de l'écran principal (A4 paysage),
 *   pages suivantes = détail de chaque bride façon "B-Champ" (A4 portrait, photos en bas,
 *   4 photos maximum au total pour l'item).
 * - Traçabilité des révisions (voir [Repository.markItemExported]) : chaque export écrit un
 *   nouveau fichier (l'export précédent n'est jamais modifié ni supprimé) et ne contient
 *   jamais une bride en double — une bride modifiée depuis le dernier export y apparaît une
 *   seule fois, marquée "RÉVISION n" ; une bride inchangée y apparaît telle quelle, sans mention.
 * Les fichiers sont écrits dans le dossier de l'application (files/exports) et
 * restent également disponibles hors-ligne (stockage local).
 */
class ExportManager(private val context: Context, private val repo: Repository) {

    // Bleu identique à celui du logo Groupe ADF (échantillonné dans logo_adf.png).
    private val colorPrimary = Color.parseColor("#203760")
    private val colorConforme = Color.parseColor("#2E7D32")
    private val colorNonConforme = Color.parseColor("#C62828")
    private val colorEnAttente = Color.parseColor("#9E9E9E")
    private val colorBackground = Color.parseColor("#F4F6F8")
    private val colorValueBox = Color.parseColor("#D3DCE8")

    // Décodé une seule fois, à une résolution proche de sa taille réelle sur la page
    // (~30pt de haut) : inutile de garder le PNG source en pleine résolution en mémoire.
    private val logoBitmap: Bitmap? by lazy {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeResource(context.resources, R.drawable.logo_adf, opts)
            opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, 240, 160)
            opts.inJustDecodeBounds = false
            BitmapFactory.decodeResource(context.resources, R.drawable.logo_adf, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun exportDir(): File {
        val dir = File(context.getExternalFilesDir(null), "exports")
        dir.mkdirs()
        return dir
    }

    // ---------------------------------------------------------------------
    // PDF
    // ---------------------------------------------------------------------

    suspend fun exportPdf(unite: String, famille: String, item: String): String {
        val header = repo.getHeader().first()
        val brides = repo.getBrides(unite, famille, item).first()
        val currentInspections = repo.getInspectionsForItem(unite, famille, item).first().associateBy { it.rep }
        // Ordre chronologique (la requête de base est triée du plus récent au plus ancien
        // pour l'affichage à l'écran ; le PDF doit lui suivre l'ordre de prise de vue).
        val photos = repo.getPhotosForItem(unite, famille, item).first().sortedBy { it.dateAjout }
        val schema = repo.getSchemaForItem(unite, famille, item).first()

        // Traçabilité des révisions : si l'item a déjà été modifié depuis son dernier export,
        // ce nouvel export documente cette révision ("ITEM-R1", etc.) — voir Repository.touchItemRevision.
        val itemRevisionState = repo.getItemRevisionOnce(unite, famille, item)
        val revision = itemRevisionState?.revision ?: 0
        val itemLabel = itemDisplayLabel(item, revision)

        // État "de base" = instantané des contrôles au dernier export (vide si l'item n'a jamais
        // été exporté). Sert uniquement à détecter quelles brides ont changé depuis — pas à les
        // afficher : ce PDF ne contient jamais deux fois la même bride (voir erratum ci-dessous).
        val alreadyExported = itemRevisionState != null && itemRevisionState.exportedRevision >= 0
        val baseInspections = if (alreadyExported) {
            repo.getInspectionBaselineForItem(unite, famille, item).associateBy { it.rep }.mapValues { it.value.toInspectionResult() }
        } else emptyMap()

        // Brides dont les contrôles/remarque ont changé depuis ce dernier export : seules elles
        // portent la mention "RÉVISION n" sur leur page détail — voir controlsDiffer().
        val revisedReps = if (alreadyExported) {
            brides.map { it.rep }.filterTo(mutableSetOf()) { rep -> controlsDiffer(baseInspections[rep], currentInspections[rep]) }
        } else emptySet()

        val doc = PdfDocument()
        var pageNumber = 0

        // Un seul récap (état actuel), marqué "RÉVISION n" si l'item est en révision.
        pageNumber = drawOverviewPages(
            doc, pageNumber, header, unite, famille, item, brides, currentInspections, schema,
            revisionLabel = if (revision > 0) revision else null
        )

        // Une seule page détail par bride (état actuel — jamais de doublon) : bride non modifiée
        // depuis le dernier export = page "de base" (sans mention) ; bride modifiée = sa version
        // actuelle, marquée "RÉVISION n".
        var photosBudget = 4
        for (bride in brides) {
            val bridePhotos = photos.filter { it.rep == bride.rep }.take(photosBudget)
            photosBudget -= bridePhotos.size

            pageNumber++
            val revisionLabel = if (bride.rep in revisedReps) revision else null
            drawBrideDetailPage(doc, pageNumber, header, unite, famille, item, bride, currentInspections[bride.rep], bridePhotos, revisionLabel)
        }

        val fileName = "PV_${itemLabel}_${System.currentTimeMillis()}.pdf"
        val file = File(exportDir(), fileName)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()

        // Fige cette révision comme nouvelle référence (et instantané des contrôles) : une
        // modification ultérieure fera apparaître la révision suivante ("-R2", ...).
        repo.markItemExported(unite, famille, item)

        return file.absolutePath
    }

    /** Contrôles ou remarque différents entre l'état "de base" (dernier export) et l'état actuel — les photos ne comptent pas (non historisées). */
    private fun controlsDiffer(base: InspectionResult?, current: InspectionResult?): Boolean {
        if (base == null && current == null) return false
        if (base == null || current == null) return true
        return base.etiMiseSerree != current.etiMiseSerree ||
            base.etiNomDateLisible != current.etiNomDateLisible ||
            base.jointMatiereConforme != current.jointMatiereConforme ||
            base.jointDimensionCentrage != current.jointDimensionCentrage ||
            base.jointAspectNeuf != current.jointAspectNeuf ||
            base.boulonNeuves != current.boulonNeuves ||
            base.boulonRondelles != current.boulonRondelles ||
            base.boulonEquilibrage != current.boulonEquilibrage ||
            base.boulonGraissage != current.boulonGraissage ||
            base.boulonLongueurDiametre != current.boulonLongueurDiametre ||
            base.boulonMatiere != current.boulonMatiere ||
            base.assemblageParallelisme != current.assemblageParallelisme ||
            base.assemblageExcentration != current.assemblageExcentration ||
            base.remarque != current.remarque
    }

    /**
     * Page 1 (+ suite si nécessaire) : affiche de l'écran principal — A4 paysage.
     * [revisionLabel] : null si l'item n'est pas en révision (bandeau/ITEM sans mention), ou le
     * numéro de révision courant de l'item (bandeau "— RÉVISION n", ITEM "-Rn").
     */
    private fun drawOverviewPages(
        doc: PdfDocument,
        startPageNumber: Int,
        header: PvHeader?,
        unite: String,
        famille: String,
        item: String,
        brides: List<BrideCatalog>,
        inspections: Map<String, InspectionResult>,
        schema: ItemSchema?,
        revisionLabel: Int?
    ): Int {
        val pageWidth = 842
        val pageHeight = 595
        var pageNumber = startPageNumber

        val leftX = 20f
        val leftWidth = 450f
        val rightX = 490f
        val rightWidth = pageWidth - 20f - rightX

        val displayItem = itemDisplayLabel(item, revisionLabel ?: 0)
        val bannerTitle = if (revisionLabel != null) "PV de JOINTAGE — RÉVISION $revisionLabel" else "PV de JOINTAGE"

        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
        var canvas = page.canvas
        canvas.drawColor(colorBackground)

        val clientLieu = listOfNotNull(
            header?.client?.trim()?.takeIf { it.isNotEmpty() },
            header?.lieu?.trim()?.takeIf { it.isNotEmpty() }
        ).joinToString(" - ")
        var y = drawBanner(canvas, pageWidth, bannerTitle, dateText = header?.date.orEmpty(), centerText = clientLieu.ifEmpty { null })
        y += 10f

        // Libellés en petit, valeurs en légèrement plus grand (et gras) juste après.
        val labelPaint = Paint().apply { textSize = 9.5f; color = Color.BLACK }
        val valuePaint = Paint().apply { textSize = 11f; color = Color.BLACK; isFakeBoldText = true }
        var cursorX = leftX
        fun drawSeg(text: String, paint: Paint) {
            canvas.drawText(text, cursorX, y, paint)
            cursorX += paint.measureText(text)
        }
        drawSeg("Unité : ", labelPaint)
        drawSeg(unite, valuePaint)
        drawSeg("      Type d'équipement : ", labelPaint)
        drawSeg(famille, valuePaint)
        drawSeg("      ITEM : ", labelPaint)
        drawSeg(displayItem, valuePaint)
        y += 14f

        // Schéma sur la moitié droite : toujours affiché à droite, sur toutes les pages
        // de l'aperçu (première page et pages "suite" en cas de liste de brides longue).
        drawSchemaPanel(canvas, rightX, rightWidth, y, pageHeight - 20f, schema)

        // Tableau des brides sur la moitié gauche.
        val headerPaint = Paint().apply { textSize = 8.2f; isFakeBoldText = true; color = Color.WHITE }
        val cellPaint = Paint().apply { textSize = 9f; color = Color.BLACK }
        val cols = tableColumns(leftX, leftWidth)

        fun drawTableHeader(c: Canvas, top: Float): Float {
            c.drawRect(leftX, top, leftX + leftWidth, top + 16f, Paint().apply { color = colorPrimary })
            drawCellText(c, "Rep.", cols[0], top, top + 16f, headerPaint, Paint.Align.LEFT)
            drawCellText(c, "Désignation", cols[1], top, top + 16f, headerPaint, Paint.Align.LEFT)
            drawCellText(c, "Etiquette", cols[2], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Joint", cols[3], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Boulonnerie", cols[4], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Assemblage", cols[5], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Conforme", cols[6], top, top + 16f, headerPaint, Paint.Align.CENTER)
            return top + 16f
        }

        var tableY = drawTableHeader(canvas, y)
        // Deux lignes par bride (désignation + DN/PN/matière du joint en dessous) : la
        // désignation dispose ainsi de sa propre ligne et n'est jamais masquée par la colonne suivante.
        val rowHeight = 26f

        for (b in brides) {
            if (tableY + rowHeight > pageHeight - 20f) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
                canvas = page.canvas
                canvas.drawColor(colorBackground)
                val bannerBottom = drawBanner(canvas, pageWidth, "$bannerTitle — suite")
                drawSchemaPanel(canvas, rightX, rightWidth, bannerBottom + 16f, pageHeight - 20f, schema)
                tableY = drawTableHeader(canvas, bannerBottom + 16f)
            }
            val insp = inspections[b.rep]
            val (etiquette, joint, boulonnerie, assemblage, global) = conformites(insp)

            drawCellText(canvas, b.rep, cols[0], tableY, tableY + rowHeight, cellPaint, Paint.Align.LEFT)
            val sousTitre = listOfNotNull(
                b.dn.takeIf { it.isNotBlank() }?.let { "DN $it" },
                b.pn.takeIf { it.isNotBlank() }?.let { "PN $it" },
                b.matiereJoint.takeIf { it.isNotBlank() }
            ).joinToString(" · ")
            drawDesignationCell(canvas, b.designation, sousTitre, cols[1], tableY, tableY + rowHeight)
            drawStatusPill(canvas, cols[2], tableY, rowHeight, etiquette)
            drawStatusPill(canvas, cols[3], tableY, rowHeight, joint)
            drawStatusPill(canvas, cols[4], tableY, rowHeight, boulonnerie)
            drawStatusPill(canvas, cols[5], tableY, rowHeight, assemblage)
            drawStatusPill(canvas, cols[6], tableY, rowHeight, global, fullText = true)

            tableY += rowHeight
        }

        doc.finishPage(page)
        return pageNumber
    }

    /** Bandeau "SCHÉMA / PLAN DE L'ÉQUIPEMENT" + image, sur la partie droite de la page, entre [top] et [bottom]. */
    private fun drawSchemaPanel(canvas: Canvas, rightX: Float, rightWidth: Float, top: Float, bottom: Float, schema: ItemSchema?) {
        val schemaTitlePaint = Paint().apply { textSize = 10f; isFakeBoldText = true; color = Color.WHITE }
        canvas.drawRect(rightX, top, rightX + rightWidth, top + 18f, Paint().apply { color = colorPrimary })
        canvas.drawText("SCHÉMA / PLAN DE L'ÉQUIPEMENT", rightX + 6f, top + 13f, schemaTitlePaint)
        val schemaBoxRect = RectF(rightX, top + 18f, rightX + rightWidth, bottom)
        canvas.drawRect(schemaBoxRect, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL })
        canvas.drawRect(schemaBoxRect, Paint().apply { color = colorEnAttente; style = Paint.Style.STROKE; strokeWidth = 1f })
        val schemaBitmap = schema?.let { loadBitmapFromPath(it.filePath, schemaBoxRect.width().toInt(), schemaBoxRect.height().toInt()) }
        if (schemaBitmap != null) {
            val dst = fitInside(schemaBitmap.width, schemaBitmap.height, schemaBoxRect.width() - 16f, schemaBoxRect.height() - 16f)
            val left = schemaBoxRect.centerX() - dst.first / 2
            val imgTop = schemaBoxRect.centerY() - dst.second / 2
            canvas.drawBitmap(schemaBitmap, null, RectF(left, imgTop, left + dst.first, imgTop + dst.second), null)
        } else {
            val emptyPaint = Paint().apply { textSize = 10f; color = colorEnAttente; textAlign = Paint.Align.CENTER }
            canvas.drawText("Aucun schéma pour cet item", schemaBoxRect.centerX(), schemaBoxRect.centerY(), emptyPaint)
        }
    }

    /** Colonnes (gauche, droite) du tableau, proportionnelles à la largeur disponible. */
    private fun tableColumns(startX: Float, totalWidth: Float): List<Pair<Float, Float>> {
        val weights = floatArrayOf(0.5f, 2.4f, 0.85f, 0.85f, 1f, 1f, 1.3f)
        val sum = weights.sum()
        var x = startX
        val result = mutableListOf<Pair<Float, Float>>()
        for (wgt in weights) {
            val w = totalWidth * (wgt / sum)
            result.add(x to (x + w))
            x += w
        }
        return result
    }

    /**
     * Détail d'une bride façon "B-Champ" — A4 portrait, avec photos en bas.
     * [revisionLabel] : null pour une bride non modifiée depuis le dernier export (page "de
     * base", bandeau/ITEM/repère sans mention), ou le numéro de révision pour une bride modifiée
     * (bandeau "— RÉVISION n", ITEM et repère "-Rn") — jamais les deux pages pour une même bride.
     */
    private fun drawBrideDetailPage(
        doc: PdfDocument,
        pageNumber: Int,
        header: PvHeader?,
        unite: String,
        famille: String,
        item: String,
        bride: BrideCatalog,
        insp: InspectionResult?,
        photos: List<Photo>,
        revisionLabel: Int?
    ) {
        val pageWidth = 595
        val pageHeight = 842
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val marginX = 24f
        val contentWidth = pageWidth - marginX * 2

        val bannerTitle = if (revisionLabel != null) {
            "PV de JOINTAGE — DÉTAIL DE LA BRIDE — RÉVISION $revisionLabel"
        } else {
            "PV de JOINTAGE — DÉTAIL DE LA BRIDE"
        }
        var y = drawBanner(canvas, pageWidth, bannerTitle, dateText = header?.date.orEmpty())
        y += 10f

        val displayItem = itemDisplayLabel(item, revisionLabel ?: 0)
        val repLabel = itemDisplayLabel(bride.rep, revisionLabel ?: 0)
        val repereBride = if (bride.designation.isNotBlank()) "$repLabel - ${bride.designation}" else repLabel

        // LOCALISATION
        y = drawSectionBar(canvas, marginX, y, contentWidth, "LOCALISATION")
        y = drawInfoRow(canvas, marginX, y, contentWidth, "Client" to header?.client.orEmpty(), "Type d'équipement" to famille)
        y = drawInfoRow(canvas, marginX, y, contentWidth, "Lieu" to header?.lieu.orEmpty(), "ITEM" to displayItem)
        y = drawInfoRow(canvas, marginX, y, contentWidth, "Unité" to unite, "Repère de la bride" to repereBride)
        y += 8f

        // ETIQUETTE
        y = drawSectionBar(canvas, marginX, y, contentWidth, "ETIQUETTE")
        y = drawEtatRow(canvas, marginX, y, contentWidth, "Etiquette mise et serrée", insp?.etiMiseSerree)
        y = drawEtatRow(canvas, marginX, y, contentWidth, "Nom (numéro GTIS) + Date lisible", insp?.etiNomDateLisible)
        y += 8f

        // JOINT — une ligne par contrôle, valeurs de référence intégrées au libellé.
        y = drawSectionBar(canvas, marginX, y, contentWidth, "JOINT")
        y = drawEtatRow(canvas, marginX, y, contentWidth, "Dimension & Centrage : DN ${bride.dn} - PN ${bride.pn}", insp?.jointDimensionCentrage)
        y = drawEtatRow(canvas, marginX, y, contentWidth, refLabel("Matière conforme au plan/spec", bride.matiereJoint), insp?.jointMatiereConforme)
        y = drawEtatRow(canvas, marginX, y, contentWidth, "Aspect du joint neuf", insp?.jointAspectNeuf)
        y += 8f

        // BOULONNERIE
        y = drawSectionBar(canvas, marginX, y, contentWidth, "BOULONNERIE")
        y = drawEtatRow(canvas, marginX, y, contentWidth, refLabel("Neuves", bride.neufBoulon), insp?.boulonNeuves)
        y = drawEtatRow(canvas, marginX, y, contentWidth, refLabel("Rondelles", bride.rondelle), insp?.boulonRondelles)
        y = drawEtatRow(canvas, marginX, y, contentWidth, "Equilibrage", insp?.boulonEquilibrage)
        y = drawEtatRow(canvas, marginX, y, contentWidth, "Graissage", insp?.boulonGraissage)
        y = drawEtatRow(canvas, marginX, y, contentWidth, longueurDiametreLabel(bride), insp?.boulonLongueurDiametre)
        y = drawEtatRow(canvas, marginX, y, contentWidth, refLabel("Matière boulonnerie", bride.matiereBoulon), insp?.boulonMatiere)
        y += 8f

        // ASSEMBLAGE — schéma de mesure à côté de chaque contrôle.
        y = drawSectionBar(canvas, marginX, y, contentWidth, "ASSEMBLAGE")
        y = drawEtatRowWithImage(canvas, marginX, y, contentWidth, R.drawable.ic_parallelisme, "Parallélisme", insp?.assemblageParallelisme)
        y = drawEtatRowWithImage(canvas, marginX, y, contentWidth, R.drawable.ic_excentration, "Excentration", insp?.assemblageExcentration)
        y += 8f

        // REMARQUE
        y = drawSectionBar(canvas, marginX, y, contentWidth, "REMARQUE :")
        y = drawWrappedText(canvas, marginX, y, contentWidth, insp?.remarque.orEmpty(), maxLines = 5)
        y += 10f

        // PHOTOS
        y = drawSectionBar(canvas, marginX, y, contentWidth, "PHOTO (ETIQUETTE & ASSEMBLAGE)")
        drawPhotoGrid(canvas, marginX, y, contentWidth, pageHeight - 20f - y, photos)

        doc.finishPage(page)
    }

    // ---------------------------------------------------------------------
    // Petits utilitaires de dessin
    // ---------------------------------------------------------------------

    /**
     * Bandeau bleu du haut, tout sur une seule ligne : logo + titre à gauche, [centerText]
     * centré (si fourni, police légèrement plus grande que le titre, ex. Client - Lieu),
     * [dateText] à droite. Le logo est centré verticalement dans le bandeau.
     * Retourne l'ordonnée Y juste sous le bandeau.
     */
    private fun drawBanner(canvas: Canvas, pageWidth: Int, title: String, dateText: String = "", centerText: String? = null): Float {
        val bannerHeight = 44f
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), bannerHeight, Paint().apply { color = colorPrimary })
        var textStartX = 16f
        logoBitmap?.let { bmp ->
            val logoH = 28f
            val logoW = logoH * bmp.width / bmp.height
            val cardH = logoH + 8f
            val cardTop = (bannerHeight - cardH) / 2f
            val cardRect = RectF(12f, cardTop, 12f + logoW + 8f, cardTop + cardH)
            canvas.drawRoundRect(cardRect, 4f, 4f, Paint().apply { color = Color.WHITE })
            canvas.drawBitmap(bmp, null, RectF(cardRect.left + 4f, cardRect.top + 4f, cardRect.right - 4f, cardRect.bottom - 4f), null)
            textStartX = cardRect.right + 10f
        }
        val titleSize = 15f
        val titlePaint = Paint().apply { color = Color.WHITE; textSize = titleSize; isFakeBoldText = true }
        canvas.drawText(title, textStartX, verticalBaseline(bannerHeight, titlePaint), titlePaint)

        if (!centerText.isNullOrEmpty()) {
            // Police légèrement plus grande que le titre, pas de libellé ("Client - Lieu" brut).
            val centerPaint = Paint().apply { color = Color.WHITE; textSize = titleSize + 2f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
            canvas.drawText(centerText, pageWidth / 2f, verticalBaseline(bannerHeight, centerPaint), centerPaint)
        }
        if (dateText.isNotEmpty()) {
            val datePaint = Paint().apply { color = Color.WHITE; textSize = 10f; textAlign = Paint.Align.RIGHT }
            canvas.drawText("Date : $dateText", pageWidth - 16f, verticalBaseline(bannerHeight, datePaint), datePaint)
        }
        return bannerHeight
    }

    /** Ordonnée de la ligne de base pour centrer verticalement du texte dans une bande de hauteur [height]. */
    private fun verticalBaseline(height: Float, paint: Paint): Float {
        val fm = paint.fontMetrics
        return height / 2f - (fm.ascent + fm.descent) / 2f
    }

    /** Texte libre multi-lignes (REMARQUE), limité à [maxLines] lignes comme sur la tablette. */
    private fun drawWrappedText(canvas: Canvas, x: Float, y: Float, width: Float, text: String, maxLines: Int): Float {
        if (text.isBlank()) return y + 6f
        val textPaint = TextPaint().apply { color = Color.BLACK; textSize = 9.5f }
        val layout = StaticLayout.Builder
            .obtain(text, 0, text.length, textPaint, width.toInt().coerceAtLeast(1))
            .setMaxLines(maxLines)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        canvas.save()
        canvas.translate(x, y + 4f)
        layout.draw(canvas)
        canvas.restore()
        return y + 4f + layout.height
    }

    private fun drawSectionBar(canvas: Canvas, x: Float, y: Float, width: Float, title: String): Float {
        val barHeight = 16f
        canvas.drawRect(x, y, x + width, y + barHeight, Paint().apply { color = colorPrimary })
        val paint = Paint().apply { color = Color.WHITE; textSize = 9.5f; isFakeBoldText = true }
        canvas.drawText(title, x + 6f, y + barHeight - 4.5f, paint)
        return y + barHeight
    }

    /** Une ligne "label [boîte bleu clair : valeur]" sur deux colonnes (LOCALISATION notamment). */
    private fun drawInfoRow(canvas: Canvas, x: Float, y: Float, width: Float, left: Pair<String, String>, right: Pair<String, String>): Float {
        val rowHeight = 19f
        val half = width / 2
        drawInfoField(canvas, x, x + 90f, half - 96f, y, rowHeight, left)
        drawInfoField(canvas, x + half, x + half + 110f, width - half - 110f, y, rowHeight, right)
        return y + rowHeight
    }

    private fun drawInfoField(canvas: Canvas, labelX: Float, boxX: Float, boxWidth: Float, y: Float, rowHeight: Float, field: Pair<String, String>) {
        if (field.first.isEmpty() || boxWidth <= 0f) return
        val labelPaint = Paint().apply { color = Color.BLACK; textSize = 9f }
        canvas.drawText(field.first, labelX, y + rowHeight - 6f, labelPaint)
        val boxRect = RectF(boxX, y + 2f, boxX + boxWidth, y + rowHeight - 2f)
        canvas.drawRect(boxRect, Paint().apply { color = colorValueBox })
        val valuePaint = Paint().apply { color = Color.BLACK; textSize = 9f; textAlign = Paint.Align.CENTER }
        val fm = valuePaint.fontMetrics
        canvas.drawText(field.second, boxRect.centerX(), boxRect.centerY() - (fm.ascent + fm.descent) / 2, valuePaint)
    }

    /** "{base} : {value}" si [value] est renseignée, sinon [base] seul. */
    private fun refLabel(base: String, value: String): String = if (value.isNotBlank()) "$base : $value" else base

    /** "Longueur / Diamètre : M{diamètre} x {longueur}" — colonnes de référence optionnelles ("LgB"/"DiamB"). */
    private fun longueurDiametreLabel(b: BrideCatalog): String {
        val label = "Longueur / Diamètre"
        if (b.diametreBoulon.isBlank() && b.longueurBoulon.isBlank()) return label
        val diam = if (b.diametreBoulon.isNotBlank()) "M${b.diametreBoulon}" else "M?"
        val longueur = b.longueurBoulon.ifBlank { "?" }
        return "$label : $diam x $longueur"
    }

    /** Ligne de contrôle avec un petit schéma juste après le libellé (ASSEMBLAGE : parallélisme / excentration). */
    private fun drawEtatRowWithImage(canvas: Canvas, x: Float, y: Float, width: Float, iconRes: Int, label: String, code: String?): Float {
        val rowHeight = 17f
        val labelPaint = Paint().apply { color = Color.BLACK; textSize = 9f }
        canvas.drawText(label, x, y + rowHeight - 5f, labelPaint)

        val imgW = 26f
        val imgH = 16f
        val iconX = x + labelPaint.measureText(label) + 8f
        val imgTop = y + (rowHeight - imgH) / 2f
        androidx.core.content.ContextCompat.getDrawable(context, iconRes)?.let { drawable ->
            drawable.setBounds(iconX.toInt(), imgTop.toInt(), (iconX + imgW).toInt(), (imgTop + imgH).toInt())
            drawable.draw(canvas)
        }

        drawEtatPill(canvas, x, y, width, rowHeight, code)
        return y + rowHeight
    }

    /** Une ligne de contrôle "libellé ................ [C/NC/A]" avec pastille colorée. */
    private fun drawEtatRow(canvas: Canvas, x: Float, y: Float, width: Float, label: String, code: String?): Float {
        val rowHeight = 17f
        val labelPaint = Paint().apply { color = Color.BLACK; textSize = 9f }
        canvas.drawText(label, x, y + rowHeight - 5f, labelPaint)
        drawEtatPill(canvas, x, y, width, rowHeight, code)
        return y + rowHeight
    }

    /** Pastille colorée C/NC/A, calée sur le bord droit de la ligne (largeur totale [width] depuis [x]). */
    private fun drawEtatPill(canvas: Canvas, x: Float, y: Float, width: Float, rowHeight: Float, code: String?) {
        val pillWidth = 50f
        val pillRect = RectF(x + width - pillWidth, y + 2f, x + width, y + rowHeight - 2f)
        val (bg, text) = when (code) {
            "O" -> colorConforme to "C"
            "N" -> colorNonConforme to "NC"
            "A" -> colorPrimary to "A"
            else -> colorEnAttente to "—"
        }
        canvas.drawRoundRect(pillRect, 3f, 3f, Paint().apply { color = bg })
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 8.5f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        val fm = textPaint.fontMetrics
        canvas.drawText(text, pillRect.centerX(), pillRect.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
    }

    /**
     * Cellule "Désignation" sur deux lignes : le libellé en gras, puis DN/PN/matière du joint
     * juste en dessous, en plus petit et grisé. Chaque ligne est réduite avec une ellipse si
     * elle dépasse la largeur de la colonne — jamais laissée à déborder (et donc être masquée
     * par la colonne suivante, qui est dessinée par-dessus).
     */
    private fun drawDesignationCell(canvas: Canvas, designation: String, sousTitre: String, col: Pair<Float, Float>, top: Float, bottom: Float) {
        val maxWidth = (col.second - col.first - 6f).coerceAtLeast(10f)
        val titrePaint = TextPaint().apply { color = Color.BLACK; textSize = 9f; isFakeBoldText = true }
        val titre = TextUtils.ellipsize(designation.ifBlank { "—" }, titrePaint, maxWidth, TextUtils.TruncateAt.END)
        if (sousTitre.isBlank()) {
            val fm = titrePaint.fontMetrics
            canvas.drawText(titre, 0, titre.length, col.first + 3f, (top + bottom) / 2 - (fm.ascent + fm.descent) / 2, titrePaint)
        } else {
            val sousTitrePaint = TextPaint().apply { color = Color.parseColor("#5A6B7A"); textSize = 7.5f }
            val sousTitreEllipse = TextUtils.ellipsize(sousTitre, sousTitrePaint, maxWidth, TextUtils.TruncateAt.END)
            val midY = (top + bottom) / 2
            canvas.drawText(titre, 0, titre.length, col.first + 3f, midY - 1f, titrePaint)
            canvas.drawText(sousTitreEllipse, 0, sousTitreEllipse.length, col.first + 3f, midY + 9f, sousTitrePaint)
        }
    }

    private fun drawCellText(canvas: Canvas, text: String, col: Pair<Float, Float>, top: Float, bottom: Float, paint: Paint, align: Paint.Align) {
        val p = Paint(paint).apply { textAlign = align }
        val fm = p.fontMetrics
        val ty = (top + bottom) / 2 - (fm.ascent + fm.descent) / 2
        val tx = when (align) {
            Paint.Align.LEFT -> col.first + 3f
            Paint.Align.CENTER -> (col.first + col.second) / 2
            Paint.Align.RIGHT -> col.second - 3f
        }
        canvas.drawText(text, tx, ty, p)
    }

    /** [fullText] : mots entiers ("CONFORME"/"NON CONFORME"/"EN ATTENTE"), utilisé pour la colonne "Conforme". */
    private fun drawStatusPill(canvas: Canvas, col: Pair<Float, Float>, top: Float, rowHeight: Float, c: Conformite, fullText: Boolean = false) {
        val rect = RectF(col.first + 2f, top + 1f, col.second - 2f, top + rowHeight - 1f)
        val bg = when (c) {
            Conformite.CONFORME -> colorConforme
            Conformite.NON_CONFORME -> colorNonConforme
            Conformite.EN_ATTENTE -> colorEnAttente
        }
        canvas.drawRect(rect, Paint().apply { color = bg })
        val text = if (fullText) {
            when (c) {
                Conformite.CONFORME -> "CONFORME"
                Conformite.NON_CONFORME -> "NON CONFORME"
                Conformite.EN_ATTENTE -> "EN ATTENTE"
            }
        } else {
            when (c) {
                Conformite.CONFORME -> "C"
                Conformite.NON_CONFORME -> "NC"
                Conformite.EN_ATTENTE -> "…"
            }
        }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = if (fullText) 7.5f else 8.5f; isFakeBoldText = fullText; textAlign = Paint.Align.CENTER }
        val fm = textPaint.fontMetrics
        canvas.drawText(text, rect.centerX(), rect.centerY() - (fm.ascent + fm.descent) / 2, textPaint)
    }

    /** Grille jusqu'à 4 photos (2 colonnes x 2 lignes) dans la zone [x, y, x+width, y+height]. */
    private fun drawPhotoGrid(canvas: Canvas, x: Float, y: Float, width: Float, height: Float, photos: List<Photo>) {
        val cols = 2
        val rows = 2
        val gap = 8f
        val cellW = (width - gap) / cols
        val cellH = (height - gap) / rows

        for (i in 0 until (cols * rows)) {
            val col = i % cols
            val row = i / cols
            val cellRect = RectF(
                x + col * (cellW + gap),
                y + row * (cellH + gap),
                x + col * (cellW + gap) + cellW,
                y + row * (cellH + gap) + cellH
            )
            canvas.drawRect(cellRect, Paint().apply { color = colorBackground; style = Paint.Style.FILL })
            canvas.drawRect(cellRect, Paint().apply { color = colorEnAttente; style = Paint.Style.STROKE; strokeWidth = 1f })

            val photo = photos.getOrNull(i)
            if (photo != null) {
                val bmp = loadBitmapFromPath(photo.filePath, cellRect.width().toInt(), cellRect.height().toInt())
                if (bmp != null) {
                    val dst = fitInside(bmp.width, bmp.height, cellRect.width() - 8f, cellRect.height() - 8f)
                    val left = cellRect.centerX() - dst.first / 2
                    val top = cellRect.centerY() - dst.second / 2
                    canvas.drawBitmap(bmp, null, RectF(left, top, left + dst.first, top + dst.second), null)
                }
            }
        }
    }

    /**
     * Charge l'image sous-échantillonnée à une résolution proche de sa taille d'affichage
     * ([reqWidth] x [reqHeight], en points PDF ≈ pixels ici) : une photo prise par l'appareil
     * fait plusieurs Mo en pleine résolution, inutile de l'embarquer telle quelle dans le PDF
     * alors qu'elle occupe au final une vignette de quelques centimètres.
     */
    private fun loadBitmapFromPath(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decodeInto(path, opts)
            opts.inSampleSize = calculateInSampleSize(opts.outWidth, opts.outHeight, reqWidth, reqHeight)
            opts.inJustDecodeBounds = false
            decodeInto(path, opts)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeInto(path: String, opts: BitmapFactory.Options): Bitmap? {
        return if (path.startsWith("content://") || path.startsWith("file://")) {
            context.contentResolver.openInputStream(Uri.parse(path))?.use { BitmapFactory.decodeStream(it, null, opts) }
        } else {
            BitmapFactory.decodeFile(path, opts)
        }
    }

    /** Plus grande puissance de 2 permettant de décoder au moins [reqWidth] x [reqHeight]. */
    private fun calculateInSampleSize(sourceWidth: Int, sourceHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (sourceWidth <= 0 || sourceHeight <= 0 || reqWidth <= 0 || reqHeight <= 0) return inSampleSize
        val halfWidth = sourceWidth / 2
        val halfHeight = sourceHeight / 2
        while (halfWidth / inSampleSize >= reqWidth && halfHeight / inSampleSize >= reqHeight) {
            inSampleSize *= 2
        }
        return inSampleSize
    }

    /** Retourne (largeur, hauteur) mises à l'échelle pour tenir dans [maxW] x [maxH] en gardant les proportions. */
    private fun fitInside(srcW: Int, srcH: Int, maxW: Float, maxH: Float): Pair<Float, Float> {
        if (srcW <= 0 || srcH <= 0) return maxW to maxH
        val scale = minOf(maxW / srcW, maxH / srcH)
        return (srcW * scale) to (srcH * scale)
    }

    private data class Conformites(
        val etiquette: Conformite,
        val joint: Conformite,
        val boulonnerie: Conformite,
        val assemblage: Conformite,
        val global: Conformite
    )

    private fun conformites(insp: InspectionResult?): Conformites {
        val etiquette = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.etiquette(
            Etat.fromCode(insp.etiMiseSerree), Etat.fromCode(insp.etiNomDateLisible)
        )
        val joint = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.joint(
            Etat.fromCode(insp.jointMatiereConforme), Etat.fromCode(insp.jointDimensionCentrage), Etat.fromCode(insp.jointAspectNeuf)
        )
        val boulonnerie = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.boulonnerie(
            Etat.fromCode(insp.boulonNeuves), Etat.fromCode(insp.boulonRondelles),
            Etat.fromCode(insp.boulonEquilibrage), Etat.fromCode(insp.boulonGraissage),
            Etat.fromCode(insp.boulonLongueurDiametre), Etat.fromCode(insp.boulonMatiere)
        )
        val assemblage = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.assemblage(
            Etat.fromCode(insp.assemblageParallelisme), Etat.fromCode(insp.assemblageExcentration)
        )
        val global = ConformiteCalculator.global(etiquette, joint, boulonnerie, assemblage)
        return Conformites(etiquette, joint, boulonnerie, assemblage, global)
    }
}
