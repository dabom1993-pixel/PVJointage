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
import com.adf.pvjointage.R
import com.adf.pvjointage.data.BrideCatalog
import com.adf.pvjointage.data.InspectionResult
import com.adf.pvjointage.data.ItemSchema
import com.adf.pvjointage.data.Photo
import com.adf.pvjointage.data.PvHeader
import com.adf.pvjointage.data.Repository
import com.adf.pvjointage.model.ConformiteCalculator
import com.adf.pvjointage.model.Conformite
import com.adf.pvjointage.model.Etat
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream

/**
 * Génère les exports du PV.
 * - CSV : reproduit la structure de l'onglet "1-Trame" (colonnes A à W, compatible Excel),
 *   colonnes K à W renseignées avec les contrôles saisis sur la tablette.
 * - PDF : un fichier par ITEM — 1ère page = affiche de l'écran principal (A4 paysage),
 *   pages suivantes = détail de chaque bride façon "B-Champ" (A4 portrait, photos en bas,
 *   4 photos maximum au total pour l'item).
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

    private val logoBitmap: Bitmap? by lazy {
        try {
            BitmapFactory.decodeResource(context.resources, R.drawable.logo_adf)
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
    // CSV — reproduit l'onglet "1-Trame" (A..W) : catalogue + contrôles bride
    // ---------------------------------------------------------------------

    suspend fun exportCsv(unite: String, famille: String, item: String): String {
        val header = repo.getHeader().first()
        val brides = repo.getBrides(unite, famille, item).first()
        val inspections = repo.getInspectionsForItem(unite, famille, item).first().associateBy { it.rep }

        val fileName = "PV_${item}_${System.currentTimeMillis()}.csv"
        val file = File(exportDir(), fileName)

        file.bufferedWriter(Charsets.UTF_8).use { w ->
            w.write("PV DE JOINTAGE\r\n")
            w.write("Client;${header?.client.orEmpty()}\r\n")
            w.write("Lieu;${header?.lieu.orEmpty()}\r\n")
            w.write("Date;${header?.date.orEmpty()}\r\n")
            w.write("Unité;$unite\r\n")
            w.write("Type d'équipement;$famille\r\n")
            w.write("ITEM;$item\r\n")
            w.write("\r\n")
            w.write(
                "Unité;Famille;Item;Rep.;Désignation;DN;PN;MatièreJ;Rondelle;MatièreB;" +
                    "Mise;Nom;MatJ;Dim;Visu;Neuve;Rond;Equi;Grais;LgDiam;MatB;Para;Exc;E;J;B;A;Conf\r\n"
            )

            for (b in brides) {
                val insp = inspections[b.rep]
                val (etiquette, joint, boulonnerie, assemblage, global) = conformites(insp)

                w.write(
                    "$unite;$famille;$item;${b.rep};${b.designation};${b.dn};${b.pn};${b.matiereJoint};${b.rondelle};${b.matiereBoulon};" +
                        "${insp?.etiMiseSerree.orEmpty()};${insp?.etiNomDateLisible.orEmpty()};" +
                        "${insp?.jointMatiereConforme.orEmpty()};${insp?.jointDimensionCentrage.orEmpty()};${insp?.jointAspectNeuf.orEmpty()};" +
                        "${insp?.boulonNeuves.orEmpty()};${insp?.boulonRondelles.orEmpty()};${insp?.boulonEquilibrage.orEmpty()};" +
                        "${insp?.boulonGraissage.orEmpty()};${insp?.boulonLongueurDiametre.orEmpty()};${insp?.boulonMatiere.orEmpty()};" +
                        "${insp?.assemblageParallelisme.orEmpty()};${insp?.assemblageExcentration.orEmpty()};" +
                        "${label(etiquette)};${label(joint)};${label(boulonnerie)};${label(assemblage)};${label(global)}\r\n"
                )
            }
        }
        return file.absolutePath
    }

    // ---------------------------------------------------------------------
    // PDF
    // ---------------------------------------------------------------------

    suspend fun exportPdf(unite: String, famille: String, item: String): String {
        val header = repo.getHeader().first()
        val brides = repo.getBrides(unite, famille, item).first()
        val inspections = repo.getInspectionsForItem(unite, famille, item).first().associateBy { it.rep }
        val photos = repo.getPhotosForItem(unite, famille, item).first()
        val schema = repo.getSchemaForItem(unite, famille, item).first()

        val doc = PdfDocument()
        var pageNumber = 0

        pageNumber = drawOverviewPages(doc, pageNumber, header, unite, famille, item, brides, inspections, schema)

        var photosBudget = 4
        for (bride in brides) {
            pageNumber++
            val bridePhotos = photos.filter { it.rep == bride.rep }.take(photosBudget)
            photosBudget -= bridePhotos.size
            drawBrideDetailPage(doc, pageNumber, header, unite, famille, item, bride, inspections[bride.rep], bridePhotos)
        }

        val fileName = "PV_${item}_${System.currentTimeMillis()}.pdf"
        val file = File(exportDir(), fileName)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file.absolutePath
    }

    /** Page 1 (+ suite si nécessaire) : affiche de l'écran principal — A4 paysage. */
    private fun drawOverviewPages(
        doc: PdfDocument,
        startPageNumber: Int,
        header: PvHeader?,
        unite: String,
        famille: String,
        item: String,
        brides: List<BrideCatalog>,
        inspections: Map<String, InspectionResult>,
        schema: ItemSchema?
    ): Int {
        val pageWidth = 842
        val pageHeight = 595
        var pageNumber = startPageNumber

        val leftX = 20f
        val leftWidth = 400f
        val rightX = 440f
        val rightWidth = pageWidth - 20f - rightX

        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
        var canvas = page.canvas
        canvas.drawColor(colorBackground)

        var y = drawBanner(
            canvas, pageWidth, "PV de JOINTAGE",
            infoLine = "Client : ${header?.client.orEmpty()}      Lieu : ${header?.lieu.orEmpty()}      Date : ${header?.date.orEmpty()}"
        )
        y += 10f

        val boldInfoPaint = Paint().apply { textSize = 10.5f; color = Color.BLACK; isFakeBoldText = true }
        canvas.drawText("Unité : $unite      Type d'équipement : $famille      ITEM : $item", leftX, y, boldInfoPaint)
        y += 14f

        // Schéma sur la moitié droite (une seule fois, sur la première page).
        val schemaTitlePaint = Paint().apply { textSize = 10f; isFakeBoldText = true; color = Color.WHITE }
        canvas.drawRect(rightX, y, rightX + rightWidth, y + 18f, Paint().apply { color = colorPrimary })
        canvas.drawText("SCHÉMA / PLAN DE L'ÉQUIPEMENT", rightX + 6f, y + 13f, schemaTitlePaint)
        val schemaBoxTop = y + 18f
        val schemaBoxBottom = pageHeight - 20f
        val schemaBoxRect = RectF(rightX, schemaBoxTop, rightX + rightWidth, schemaBoxBottom)
        canvas.drawRect(schemaBoxRect, Paint().apply { color = Color.WHITE; style = Paint.Style.FILL })
        canvas.drawRect(schemaBoxRect, Paint().apply { color = colorEnAttente; style = Paint.Style.STROKE; strokeWidth = 1f })
        val schemaBitmap = schema?.let { loadBitmapFromPath(it.filePath) }
        if (schemaBitmap != null) {
            val dst = fitInside(schemaBitmap.width, schemaBitmap.height, schemaBoxRect.width() - 16f, schemaBoxRect.height() - 16f)
            val left = schemaBoxRect.centerX() - dst.first / 2
            val top = schemaBoxRect.centerY() - dst.second / 2
            canvas.drawBitmap(schemaBitmap, null, RectF(left, top, left + dst.first, top + dst.second), null)
        } else {
            val emptyPaint = Paint().apply { textSize = 10f; color = colorEnAttente; textAlign = Paint.Align.CENTER }
            canvas.drawText("Aucun schéma pour cet item", schemaBoxRect.centerX(), schemaBoxRect.centerY(), emptyPaint)
        }

        // Tableau des brides sur la moitié gauche.
        val headerPaint = Paint().apply { textSize = 9f; isFakeBoldText = true; color = Color.WHITE }
        val cellPaint = Paint().apply { textSize = 9f; color = Color.BLACK }
        val cols = tableColumns(leftX, leftWidth)

        fun drawTableHeader(c: Canvas, top: Float): Float {
            c.drawRect(leftX, top, leftX + leftWidth, top + 16f, Paint().apply { color = colorPrimary })
            drawCellText(c, "Rep.", cols[0], top, top + 16f, headerPaint, Paint.Align.LEFT)
            drawCellText(c, "Désignation", cols[1], top, top + 16f, headerPaint, Paint.Align.LEFT)
            drawCellText(c, "Etiq.", cols[2], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Joint", cols[3], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Boul.", cols[4], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Assem.", cols[5], top, top + 16f, headerPaint, Paint.Align.CENTER)
            drawCellText(c, "Conforme", cols[6], top, top + 16f, headerPaint, Paint.Align.CENTER)
            return top + 16f
        }

        var tableY = drawTableHeader(canvas, y)
        val rowHeight = 16f

        for (b in brides) {
            if (tableY + rowHeight > pageHeight - 20f) {
                doc.finishPage(page)
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, ++pageNumber).create())
                canvas = page.canvas
                canvas.drawColor(colorBackground)
                val bannerBottom = drawBanner(canvas, pageWidth, "PV de JOINTAGE — suite")
                tableY = drawTableHeader(canvas, bannerBottom + 16f)
            }
            val insp = inspections[b.rep]
            val (etiquette, joint, boulonnerie, assemblage, global) = conformites(insp)

            drawCellText(canvas, b.rep, cols[0], tableY, tableY + rowHeight, cellPaint, Paint.Align.LEFT)
            drawCellText(canvas, b.designation, cols[1], tableY, tableY + rowHeight, cellPaint, Paint.Align.LEFT)
            drawStatusPill(canvas, cols[2], tableY, rowHeight, etiquette)
            drawStatusPill(canvas, cols[3], tableY, rowHeight, joint)
            drawStatusPill(canvas, cols[4], tableY, rowHeight, boulonnerie)
            drawStatusPill(canvas, cols[5], tableY, rowHeight, assemblage)
            drawStatusPill(canvas, cols[6], tableY, rowHeight, global)

            tableY += rowHeight
        }

        doc.finishPage(page)
        return pageNumber
    }

    /** Colonnes (gauche, droite) du tableau, proportionnelles à la largeur disponible. */
    private fun tableColumns(startX: Float, totalWidth: Float): List<Pair<Float, Float>> {
        val weights = floatArrayOf(0.6f, 1.6f, 0.9f, 0.9f, 0.9f, 0.9f, 1f)
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

    /** Détail d'une bride façon "B-Champ" — A4 portrait, avec photos en bas. */
    private fun drawBrideDetailPage(
        doc: PdfDocument,
        pageNumber: Int,
        header: PvHeader?,
        unite: String,
        famille: String,
        item: String,
        bride: BrideCatalog,
        insp: InspectionResult?,
        photos: List<Photo>
    ) {
        val pageWidth = 595
        val pageHeight = 842
        val page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val marginX = 24f
        val contentWidth = pageWidth - marginX * 2

        var y = drawBanner(canvas, pageWidth, "PV de JOINTAGE — DÉTAIL DE LA BRIDE", dateText = header?.date.orEmpty())
        y += 10f

        val repereBride = if (bride.designation.isNotBlank()) "${bride.rep} - ${bride.designation}" else bride.rep

        // LOCALISATION
        y = drawSectionBar(canvas, marginX, y, contentWidth, "LOCALISATION")
        y = drawInfoRow(canvas, marginX, y, contentWidth, "Client" to header?.client.orEmpty(), "Type d'équipement" to famille)
        y = drawInfoRow(canvas, marginX, y, contentWidth, "Lieu" to header?.lieu.orEmpty(), "ITEM" to item)
        y = drawInfoRow(canvas, marginX, y, contentWidth, "Unité" to unite, "Repère de la bride" to repereBride)
        y += 8f

        // ETIQUETTE
        y = drawSectionBar(canvas, marginX, y, contentWidth, "ETIQUETTE")
        y = drawTwoEtatRow(canvas, marginX, y, contentWidth, "Etiquette mise et serrée", insp?.etiMiseSerree, "Nom (numéro GTIS) + Date lisible", insp?.etiNomDateLisible)
        y += 8f

        // JOINT
        y = drawSectionBar(canvas, marginX, y, contentWidth, "JOINT")
        y = drawRefBoxRow(canvas, marginX, y, contentWidth, listOf("DN" to bride.dn, "PN" to bride.pn, "Matière" to bride.matiereJoint))
        y = drawTwoEtatRow(canvas, marginX, y, contentWidth, "Matière conforme au plan/spec", insp?.jointMatiereConforme, "Dimension & centrage", insp?.jointDimensionCentrage)
        y = drawTwoEtatRow(canvas, marginX, y, contentWidth, "Aspect du joint neuf", insp?.jointAspectNeuf, null, null)
        y += 8f

        // BOULONNERIE
        y = drawSectionBar(canvas, marginX, y, contentWidth, "BOULONNERIE")
        y = drawRefBoxRow(canvas, marginX, y, contentWidth, listOf("Rondelle" to bride.rondelle, "Matière" to bride.matiereBoulon))
        y = drawTwoEtatRow(canvas, marginX, y, contentWidth, "Neuves", insp?.boulonNeuves, "Rondelles", insp?.boulonRondelles)
        y = drawTwoEtatRow(canvas, marginX, y, contentWidth, "Equilibrage", insp?.boulonEquilibrage, "Graissage", insp?.boulonGraissage)
        y = drawTwoEtatRow(canvas, marginX, y, contentWidth, "Longueur / Diamètre", insp?.boulonLongueurDiametre, "Matière", insp?.boulonMatiere)
        y += 8f

        // ASSEMBLAGE
        y = drawSectionBar(canvas, marginX, y, contentWidth, "ASSEMBLAGE")
        y = drawTwoEtatRow(canvas, marginX, y, contentWidth, "Parallélisme", insp?.assemblageParallelisme, "Excentration", insp?.assemblageExcentration)
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
     * Bandeau bleu du haut (logo + titre, écriture blanche façon "PV de JOINTAGE").
     * Si [infoLine] est fourni (page de garde), il est affiché sur une 2e ligne, même style
     * que le titre. Sinon, [dateText] (pages de détail bride) s'affiche seule en haut à droite.
     * Retourne l'ordonnée Y juste sous le bandeau.
     */
    private fun drawBanner(canvas: Canvas, pageWidth: Int, title: String, dateText: String = "", infoLine: String? = null): Float {
        val bannerHeight = if (infoLine != null) 62f else 44f
        canvas.drawRect(0f, 0f, pageWidth.toFloat(), bannerHeight, Paint().apply { color = colorPrimary })
        var textStartX = 16f
        logoBitmap?.let { bmp ->
            val logoH = 28f
            val logoW = logoH * bmp.width / bmp.height
            val cardRect = RectF(12f, 8f, 12f + logoW + 8f, 8f + logoH + 8f)
            canvas.drawRoundRect(cardRect, 4f, 4f, Paint().apply { color = Color.WHITE })
            canvas.drawBitmap(bmp, null, RectF(cardRect.left + 4f, cardRect.top + 4f, cardRect.right - 4f, cardRect.bottom - 4f), null)
            textStartX = cardRect.right + 10f
        }
        val titlePaint = Paint().apply { color = Color.WHITE; textSize = 15f; isFakeBoldText = true }
        canvas.drawText(title, textStartX, 26f, titlePaint)
        if (infoLine != null) {
            // Même style d'écriture que le titre (blanc, gras), sur la 2e ligne du bandeau.
            val infoPaint = Paint().apply { color = Color.WHITE; textSize = 12f; isFakeBoldText = true }
            canvas.drawText(infoLine, textStartX, 46f, infoPaint)
        }
        if (dateText.isNotEmpty()) {
            val datePaint = Paint().apply { color = Color.WHITE; textSize = 10f; textAlign = Paint.Align.RIGHT }
            canvas.drawText("Date : $dateText", pageWidth - 16f, bannerHeight / 2 + 4f, datePaint)
        }
        return bannerHeight
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

    /** Une ligne de 2 à 3 valeurs de référence en boîte bleu clair (DN/PN/Matière, Rondelle/Matière...). */
    private fun drawRefBoxRow(canvas: Canvas, x: Float, y: Float, width: Float, fields: List<Pair<String, String>>): Float {
        val rowHeight = 22f
        val colWidth = width / fields.size
        fields.forEachIndexed { i, (label, value) ->
            val colX = x + i * colWidth
            val labelPaint = Paint().apply { color = Color.DKGRAY; textSize = 8.5f }
            canvas.drawText(label, colX, y + 9f, labelPaint)
            val boxRect = RectF(colX, y + 11f, colX + colWidth - 8f, y + rowHeight)
            canvas.drawRect(boxRect, Paint().apply { color = colorValueBox })
            val valuePaint = Paint().apply { color = Color.BLACK; textSize = 8.5f; textAlign = Paint.Align.CENTER }
            val fm = valuePaint.fontMetrics
            canvas.drawText(value, boxRect.centerX(), boxRect.centerY() - (fm.ascent + fm.descent) / 2, valuePaint)
        }
        return y + rowHeight + 3f
    }

    /** Deux lignes de contrôle côte à côte (OUI vert / NON rouge / — gris), comme sur le modèle B-Champ. */
    private fun drawTwoEtatRow(canvas: Canvas, x: Float, y: Float, width: Float, leftLabel: String, leftCode: String?, rightLabel: String?, rightCode: String?): Float {
        val half = width / 2
        val bottomLeft = drawEtatRow(canvas, x, y, half - 6f, leftLabel, leftCode)
        val bottomRight = if (rightLabel != null) drawEtatRow(canvas, x + half + 6f, y, half - 6f, rightLabel, rightCode) else bottomLeft
        return maxOf(bottomLeft, bottomRight)
    }

    /** Une ligne de contrôle "libellé ................ [OUI/NON/A]" avec pastille colorée. */
    private fun drawEtatRow(canvas: Canvas, x: Float, y: Float, width: Float, label: String, code: String?): Float {
        val rowHeight = 17f
        val labelPaint = Paint().apply { color = Color.BLACK; textSize = 9f }
        canvas.drawText(label, x, y + rowHeight - 5f, labelPaint)

        val pillWidth = 50f
        val pillRect = RectF(x + width - pillWidth, y + 2f, x + width, y + rowHeight - 2f)
        val (bg, text) = when (code) {
            "O" -> colorConforme to "OUI"
            "N" -> colorNonConforme to "NON"
            "A" -> colorPrimary to "A"
            else -> colorEnAttente to "—"
        }
        canvas.drawRoundRect(pillRect, 3f, 3f, Paint().apply { color = bg })
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 8.5f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
        val fm = textPaint.fontMetrics
        canvas.drawText(text, pillRect.centerX(), pillRect.centerY() - (fm.ascent + fm.descent) / 2, textPaint)

        return y + rowHeight
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

    private fun drawStatusPill(canvas: Canvas, col: Pair<Float, Float>, top: Float, rowHeight: Float, c: Conformite) {
        val rect = RectF(col.first + 2f, top + 1f, col.second - 2f, top + rowHeight - 1f)
        val bg = when (c) {
            Conformite.CONFORME -> colorConforme
            Conformite.NON_CONFORME -> colorNonConforme
            Conformite.EN_ATTENTE -> colorEnAttente
        }
        canvas.drawRect(rect, Paint().apply { color = bg })
        val text = when (c) {
            Conformite.CONFORME -> "OK"
            Conformite.NON_CONFORME -> "NC"
            Conformite.EN_ATTENTE -> "…"
        }
        val textPaint = Paint().apply { color = Color.WHITE; textSize = 8.5f; textAlign = Paint.Align.CENTER }
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
                val bmp = loadBitmapFromPath(photo.filePath)
                if (bmp != null) {
                    val dst = fitInside(bmp.width, bmp.height, cellRect.width() - 8f, cellRect.height() - 8f)
                    val left = cellRect.centerX() - dst.first / 2
                    val top = cellRect.centerY() - dst.second / 2
                    canvas.drawBitmap(bmp, null, RectF(left, top, left + dst.first, top + dst.second), null)
                }
            }
        }
    }

    private fun loadBitmapFromPath(path: String): Bitmap? {
        return try {
            if (path.startsWith("content://") || path.startsWith("file://")) {
                context.contentResolver.openInputStream(Uri.parse(path))?.use { BitmapFactory.decodeStream(it) }
            } else {
                BitmapFactory.decodeFile(path)
            }
        } catch (e: Exception) {
            null
        }
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

    private fun label(c: Conformite): String = when (c) {
        Conformite.CONFORME -> "CONFORME"
        Conformite.NON_CONFORME -> "NON CONFORME"
        Conformite.EN_ATTENTE -> "EN ATTENTE"
    }
}
