package com.adf.pvjointage.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import com.adf.pvjointage.data.Repository
import com.adf.pvjointage.model.ConformiteCalculator
import com.adf.pvjointage.model.Conformite
import com.adf.pvjointage.model.Etat
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream

/**
 * Génère les exports du PV.
 * - CSV : reproduit la structure de l'onglet "1-Exemple" (compatible Excel).
 * - PDF : rapport imprimable avec statuts de contrôle + photos de l'onglet "1-Plan".
 * Les fichiers sont écrits dans le dossier de l'application (files/exports) et
 * restent également disponibles hors-ligne (stockage local).
 */
class ExportManager(private val context: Context, private val repo: Repository) {

    private fun exportDir(): File {
        val dir = File(context.getExternalFilesDir(null), "exports")
        dir.mkdirs()
        return dir
    }

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
            w.write("Fait par;${header?.faitPar.orEmpty()}\r\n")
            w.write("Unité;$unite\r\n")
            w.write("Type d'équipement;$famille\r\n")
            w.write("ITEM;$item\r\n")
            w.write("\r\n")
            w.write("Rep.;Désignation;DN;PN;Matière Joint;Rondelle;Matière Boulon;Etiquette;Joint;Boulonnerie;Assemblage;Conforme\r\n")

            for (b in brides) {
                val insp = inspections[b.rep]
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

                w.write(
                    "${b.rep};${b.designation};${b.dn};${b.pn};${b.matiereJoint};${b.rondelle};${b.matiereBoulon};" +
                        "${label(etiquette)};${label(joint)};${label(boulonnerie)};${label(assemblage)};${label(global)}\r\n"
                )
            }
        }
        return file.absolutePath
    }

    suspend fun exportPdf(unite: String, famille: String, item: String): String {
        val header = repo.getHeader().first()
        val brides = repo.getBrides(unite, famille, item).first()
        val inspections = repo.getInspectionsForItem(unite, famille, item).first().associateBy { it.rep }
        val photos = repo.getPhotosForItem(unite, famille, item).first()

        val doc = PdfDocument()
        val pageWidth = 842 // A4 paysage approx points
        val pageHeight = 595
        val paintTitle = Paint().apply { textSize = 18f; isFakeBoldText = true }
        val paintText = Paint().apply { textSize = 11f }
        val paintHeader = Paint().apply { textSize = 12f; isFakeBoldText = true }

        var pageNumber = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
        var canvas: Canvas = page.canvas
        var y = 40f

        canvas.drawText("PV DE JOINTAGE — ITEM $item", 30f, y, paintTitle); y += 26f
        canvas.drawText("Client : ${header?.client.orEmpty()}    Lieu : ${header?.lieu.orEmpty()}    Date : ${header?.date.orEmpty()}    Fait par : ${header?.faitPar.orEmpty()}", 30f, y, paintText); y += 20f
        canvas.drawText("Unité : $unite    Type d'équipement : $famille    ITEM : $item", 30f, y, paintText); y += 26f

        canvas.drawText(String.format("%-6s %-30s %-16s %-16s", "Rep.", "Désignation", "Statut", "Conforme"), 30f, y, paintHeader); y += 18f

        for (b in brides) {
            if (y > pageHeight - 40) {
                doc.finishPage(page)
                pageNumber++
                page = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                canvas = page.canvas
                y = 40f
            }
            val insp = inspections[b.rep]
            val etiquette = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.etiquette(Etat.fromCode(insp.etiMiseSerree), Etat.fromCode(insp.etiNomDateLisible))
            val joint = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.joint(Etat.fromCode(insp.jointMatiereConforme), Etat.fromCode(insp.jointDimensionCentrage), Etat.fromCode(insp.jointAspectNeuf))
            val boulonnerie = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.boulonnerie(Etat.fromCode(insp.boulonNeuves), Etat.fromCode(insp.boulonRondelles), Etat.fromCode(insp.boulonEquilibrage), Etat.fromCode(insp.boulonGraissage), Etat.fromCode(insp.boulonLongueurDiametre), Etat.fromCode(insp.boulonMatiere))
            val assemblage = if (insp == null) Conformite.EN_ATTENTE else ConformiteCalculator.assemblage(Etat.fromCode(insp.assemblageParallelisme), Etat.fromCode(insp.assemblageExcentration))
            val global = ConformiteCalculator.global(etiquette, joint, boulonnerie, assemblage)

            canvas.drawText(
                String.format("%-6s %-30s E:%s J:%s B:%s A:%s   %-10s", b.rep, b.designation.take(30), label(etiquette), label(joint), label(boulonnerie), label(assemblage), label(global)),
                30f, y, paintText
            )
            y += 16f
        }
        doc.finishPage(page)

        // Page(s) photos
        for (p in photos) {
            try {
                val uri = Uri.parse(p.filePath)
                val input = context.contentResolver.openInputStream(uri) ?: continue
                val bmp = BitmapFactory.decodeStream(input)
                input.close()
                pageNumber++
                val photoPage = doc.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create())
                val c = photoPage.canvas
                c.drawText("Photo — ITEM $item", 30f, 30f, paintHeader)
                val maxW = pageWidth - 60f
                val maxH = pageHeight - 80f
                val scale = minOf(maxW / bmp.width, maxH / bmp.height)
                val dstW = (bmp.width * scale).toInt()
                val dstH = (bmp.height * scale).toInt()
                val scaledBmp = android.graphics.Bitmap.createScaledBitmap(bmp, dstW, dstH, true)
                c.drawBitmap(scaledBmp, 30f, 50f, null)
                doc.finishPage(photoPage)
            } catch (e: Exception) {
                // ignore unreadable photo
            }
        }

        val fileName = "PV_${item}_${System.currentTimeMillis()}.pdf"
        val file = File(exportDir(), fileName)
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        return file.absolutePath
    }

    private fun label(c: Conformite): String = when (c) {
        Conformite.CONFORME -> "CONFORME"
        Conformite.NON_CONFORME -> "NON CONFORME"
        Conformite.EN_ATTENTE -> "EN ATTENTE"
    }
}
