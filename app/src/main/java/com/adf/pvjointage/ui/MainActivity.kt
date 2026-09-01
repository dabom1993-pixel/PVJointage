package com.adf.pvjointage.ui

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.adf.pvjointage.PvApp
import com.adf.pvjointage.R
import com.adf.pvjointage.data.ExcelImporter
import com.adf.pvjointage.data.PvHeader
import com.adf.pvjointage.data.Repository
import com.adf.pvjointage.databinding.ActivityMainBinding
import com.adf.pvjointage.databinding.DialogCatalogueBinding
import com.adf.pvjointage.databinding.DialogPdfExportBinding
import com.adf.pvjointage.databinding.DialogSchemaZoomBinding
import com.adf.pvjointage.export.ExportManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val repo by lazy { (application as PvApp).repository }
    private val adapter = BrideAdapter { bride ->
        val i = Intent(this, ChampActivity::class.java)
        i.putExtra("unite", bride.unite)
        i.putExtra("famille", bride.famille)
        i.putExtra("item", bride.item)
        i.putExtra("rep", bride.rep)
        startActivity(i)
    }

    private var currentHeader = PvHeader()
    private var selectedUnite: String = ""
    private var selectedFamille: String = ""
    private var selectedItem: String = ""
    private var suppressSpinnerEvents = false
    private var schemaJob: kotlinx.coroutines.Job? = null
    private var currentSchemaFile: File? = null

    private val schemaGestureDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                openSchemaFullscreen()
                return true
            }
        })
    }

    private var pendingWorkbook: ExcelImporter.OpenWorkbook? = null
    private var pendingSheetName: String? = null

    private val pickExcelFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) openWorkbookAndChooseSheet(uri)
    }

    /** Dossier des images de schémas (un fichier par ITEM) : demandé juste après le choix de l'onglet. */
    private val pickSchemasFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        val workbook = pendingWorkbook
        val sheetName = pendingSheetName
        pendingWorkbook = null
        pendingSheetName = null
        if (workbook != null && sheetName != null) importFromExcel(workbook, sheetName, treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.rvBrides.layoutManager = LinearLayoutManager(this)
        binding.rvBrides.adapter = adapter

        // Pas d'import automatique de données de démonstration : au premier lancement, la
        // tablette doit rester vide (aucun item, aucun schéma) tant que rien n'a été importé.
        lifecycleScope.launch { observeHeader(); observeUnites() }

        binding.imgSchema.setOnTouchListener { _, event ->
            schemaGestureDetector.onTouchEvent(event)
            true
        }

        binding.btnCatalogue.setOnClickListener { showCatalogueDialog() }

        observeBridesAndInspections()
    }

    private fun saveHeader() {
        lifecycleScope.launch { repo.saveHeader(currentHeader) }
    }

    private fun observeHeader() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.getHeader().collect { header ->
                    if (header != null) {
                        currentHeader = header
                        if (binding.etClient.text.toString() != header.client) binding.etClient.setText(header.client)
                        if (binding.etLieu.text.toString() != header.lieu) binding.etLieu.setText(header.lieu)
                        if (binding.etDate.text.toString() != header.date) binding.etDate.setText(header.date)
                        if (header.uniteSelectionnee.isNotBlank() && selectedUnite.isBlank()) {
                            selectedUnite = header.uniteSelectionnee
                            selectedFamille = header.familleSelectionnee
                            selectedItem = header.itemSelectionne
                        }
                    }
                }
            }
        }
    }

    private var unitesJob: kotlinx.coroutines.Job? = null

    private fun observeUnites() {
        unitesJob?.cancel()
        unitesJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.getUnites().collect { unites ->
                    populateSpinner(binding.spUnite, unites, { selectedUnite }) { chosen ->
                        selectedUnite = chosen
                        selectedFamille = ""
                        selectedItem = ""
                        observeFamilles()
                    }
                    if (unites.isNotEmpty() && selectedUnite.isBlank()) {
                        selectedUnite = unites.first()
                        observeFamilles()
                    }
                }
            }
        }
    }

    private var famillesJob: kotlinx.coroutines.Job? = null

    private fun observeFamilles() {
        famillesJob?.cancel()
        famillesJob = lifecycleScope.launch {
            repo.getFamilles(selectedUnite).collect { familles ->
                populateSpinner(binding.spFamille, familles, { selectedFamille }) { chosen ->
                    selectedFamille = chosen
                    selectedItem = ""
                    observeItems()
                }
                if (familles.isNotEmpty() && selectedFamille.isBlank()) {
                    selectedFamille = familles.first()
                    observeItems()
                }
            }
        }
    }

    private var itemsJob: kotlinx.coroutines.Job? = null

    private fun observeItems() {
        itemsJob?.cancel()
        itemsJob = lifecycleScope.launch {
            repo.getItems(selectedUnite, selectedFamille).collect { items ->
                populateSpinner(binding.spItem, items, { selectedItem }) { chosen ->
                    selectedItem = chosen
                    persistSelection()
                    observeBridesAndInspections()
                    observeSchema()
                }
                if (items.isNotEmpty() && selectedItem.isBlank()) {
                    selectedItem = items.first()
                    persistSelection()
                }
                if (selectedItem.isNotBlank()) {
                    observeBridesAndInspections()
                    observeSchema()
                }
            }
        }
    }

    private fun persistSelection() {
        currentHeader = currentHeader.copy(
            uniteSelectionnee = selectedUnite,
            familleSelectionnee = selectedFamille,
            itemSelectionne = selectedItem
        )
        saveHeader()
    }

    /**
     * [currentSelection] est relu à chaque sélection (et non figé au moment de l'appel) :
     * sinon, après une navigation directe (fenêtre Catalogue), la comparaison restait bloquée
     * sur l'ancienne valeur et resélectionner ce même item ne déclenchait plus rien.
     */
    private fun populateSpinner(spinner: android.widget.Spinner, values: List<String>, currentSelection: () -> String, onSelected: (String) -> Unit) {
        val selected = currentSelection()
        val adapterSp = ArrayAdapter(this, android.R.layout.simple_spinner_item, values)
        adapterSp.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        suppressSpinnerEvents = true
        spinner.adapter = adapterSp
        val idx = values.indexOf(selected)
        if (idx >= 0) spinner.setSelection(idx)
        suppressSpinnerEvents = false
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (suppressSpinnerEvents) return
                val value = values.getOrNull(position) ?: return
                if (value != currentSelection()) onSelected(value)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private var bridesJob: kotlinx.coroutines.Job? = null

    private fun observeBridesAndInspections() {
        bridesJob?.cancel()
        if (selectedUnite.isBlank() || selectedFamille.isBlank() || selectedItem.isBlank()) return
        bridesJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    repo.getBrides(selectedUnite, selectedFamille, selectedItem),
                    repo.getInspectionsForItem(selectedUnite, selectedFamille, selectedItem)
                ) { brides, inspections ->
                    val byRep = inspections.associateBy { it.rep }
                    brides.map { BrideRow(it, byRep[it.rep]) }
                }.collect { rows -> adapter.submit(rows) }
            }
        }
    }

    private fun observeSchema() {
        schemaJob?.cancel()
        if (selectedUnite.isBlank() || selectedItem.isBlank()) return
        schemaJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repo.getSchemaForItem(selectedUnite, selectedFamille, selectedItem).collect { schema ->
                    if (schema != null && File(schema.filePath).exists()) {
                        currentSchemaFile = File(schema.filePath)
                        binding.imgSchema.load(currentSchemaFile)
                        binding.imgSchema.visibility = View.VISIBLE
                        binding.emptySchemaLayout.visibility = View.GONE
                    } else {
                        currentSchemaFile = null
                        binding.imgSchema.visibility = View.GONE
                        binding.emptySchemaLayout.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    /** Affiche le schéma en plein écran, avec zoom (pincement à deux doigts) et fermeture par la croix. */
    private fun openSchemaFullscreen() {
        val file = currentSchemaFile ?: return
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val dialogBinding = DialogSchemaZoomBinding.inflate(layoutInflater)
        dialog.setContentView(dialogBinding.root)
        dialogBinding.imgZoom.load(file) {
            listener(onSuccess = { _, _ -> dialogBinding.imgZoom.fitToView() })
        }
        dialogBinding.btnFermerZoom.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private enum class CatalogueFilter { TOUS, COMPLETS, INCOMPLETS }

    /** Fenêtre listant tout le catalogue (Unité × Famille → Items), avec filtre et navigation directe. */
    private fun showCatalogueDialog() {
        val dialogBinding = DialogCatalogueBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogBinding.root)
        dialogBinding.btnFermerCatalogue.setOnClickListener { dialog.dismiss() }

        lifecycleScope.launch {
            val entries = repo.getCatalogueOverview()

            fun render() {
                val filter = when (dialogBinding.filterGroup.checkedRadioButtonId) {
                    R.id.filterComplets -> CatalogueFilter.COMPLETS
                    R.id.filterIncomplets -> CatalogueFilter.INCOMPLETS
                    else -> CatalogueFilter.TOUS
                }
                buildCatalogueTable(dialogBinding.catalogueTable, entries, filter) { unite, famille, item ->
                    navigateToItem(unite, famille, item)
                    dialog.dismiss()
                }
            }

            dialogBinding.filterGroup.setOnCheckedChangeListener { _, _ -> render() }
            render()
        }
        dialog.show()
    }

    /** Construit dynamiquement le tableau Unité (lignes) × Famille (colonnes), items cliquables colorés selon leur complétude. */
    private fun buildCatalogueTable(
        table: TableLayout,
        entries: List<Repository.CatalogueEntry>,
        filter: CatalogueFilter,
        onItemClick: (unite: String, famille: String, item: String) -> Unit
    ) {
        table.removeAllViews()
        val filtered = when (filter) {
            CatalogueFilter.TOUS -> entries
            CatalogueFilter.COMPLETS -> entries.filter { it.complete }
            CatalogueFilter.INCOMPLETS -> entries.filter { !it.complete }
        }

        if (filtered.isEmpty()) {
            table.addView(TextView(this).apply {
                text = getString(R.string.catalogue_vide)
                setPadding(24, 24, 24, 24)
            })
            return
        }

        val unites = filtered.map { it.unite }.distinct().sorted()
        val familles = filtered.map { it.famille }.distinct().sorted()

        val headerRow = TableRow(this)
        headerRow.addView(catalogueHeaderCell(""))
        familles.forEach { headerRow.addView(catalogueHeaderCell(it)) }
        table.addView(headerRow)

        unites.forEach { unite ->
            val row = TableRow(this)
            row.addView(catalogueHeaderCell(unite))
            familles.forEach { famille ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 8, 12, 8)
                }
                filtered.filter { it.unite == unite && it.famille == famille }
                    .sortedBy { it.item }
                    .forEach { entry ->
                        cell.addView(TextView(this@MainActivity).apply {
                            text = entry.item
                            setTextColor(if (entry.complete) ContextCompat.getColor(this@MainActivity, R.color.conforme) else Color.RED)
                            textSize = 13f
                            setPadding(6, 4, 6, 4)
                            setOnClickListener { onItemClick(entry.unite, entry.famille, entry.item) }
                        })
                    }
                row.addView(cell)
            }
            table.addView(row)
        }
    }

    private fun catalogueHeaderCell(text: String): TextView = TextView(this).apply {
        this.text = text
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(20, 14, 20, 14)
        setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.primary))
        setTextColor(Color.WHITE)
    }

    /** Fait pointer les 3 sélecteurs (Unité/Famille/ITEM) directement sur cet item et rafraîchit l'écran. */
    private fun navigateToItem(unite: String, famille: String, item: String) {
        bridesJob?.cancel()
        schemaJob?.cancel()
        selectedUnite = unite
        selectedFamille = famille
        selectedItem = item
        persistSelection()
        observeUnites()
        observeFamilles()
        observeItems()
        observeBridesAndInspections()
        observeSchema()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        // Forcer le blanc directement sur le texte des items (plus fiable sur certains
        // appareils que le seul attribut app:actionMenuTextColor du Toolbar).
        for (i in 0 until menu.size()) {
            val menuItem = menu.getItem(i)
            val title = menuItem.title ?: continue
            val spannable = android.text.SpannableString(title)
            spannable.setSpan(
                android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),
                0, spannable.length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            menuItem.title = spannable
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_import) {
            confirmImport()
            return true
        }
        if (item.itemId == R.id.action_export) {
            val options = arrayOf(getString(R.string.export_option_excel), getString(R.string.export_option_pdf))
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_export))
                .setItems(options) { _, which ->
                    if (which == 0) {
                        lifecycleScope.launch {
                            try {
                                val path = repo.exportNativeExcel()
                                android.widget.Toast.makeText(this@MainActivity, getString(R.string.export_done, path), android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                android.widget.Toast.makeText(this@MainActivity, getString(R.string.export_erreur, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        showPdfExportDialog()
                    }
                }
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private val pdfExportSelection = mutableSetOf<Triple<String, String, String>>()

    /** Fenêtre d'impression PDF (façon "Filtre") : sélection multiple d'items, impression groupée (1 PDF par item). */
    private fun showPdfExportDialog() {
        pdfExportSelection.clear()
        val dialogBinding = DialogPdfExportBinding.inflate(layoutInflater)
        val dialog = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(dialogBinding.root)
        dialogBinding.btnFermerPdfExport.setOnClickListener { dialog.dismiss() }

        lifecycleScope.launch {
            val entries = repo.getCatalogueOverview()

            fun render() {
                buildPdfExportTable(dialogBinding.pdfExportTable, entries) { render() }
                dialogBinding.tvSelectionCount.text = getString(R.string.pdf_export_selection_count, pdfExportSelection.size)
            }

            dialogBinding.btnToutSelectionner.setOnClickListener {
                pdfExportSelection.clear()
                entries.forEach { pdfExportSelection.add(Triple(it.unite, it.famille, it.item)) }
                render()
            }
            dialogBinding.btnToutDeselectionner.setOnClickListener {
                pdfExportSelection.clear()
                render()
            }
            dialogBinding.btnImprimer.setOnClickListener {
                if (pdfExportSelection.isEmpty()) {
                    android.widget.Toast.makeText(this@MainActivity, R.string.pdf_export_aucune_selection, android.widget.Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val selection = pdfExportSelection.toList()
                dialog.dismiss()
                printSelectedItems(selection)
            }

            render()
        }
        dialog.show()
    }

    /** Grille Unité (lignes) × Famille (colonnes) : touche = bascule la sélection (item seul, ou tous les items d'une famille/unité). */
    private fun buildPdfExportTable(
        table: TableLayout,
        entries: List<Repository.CatalogueEntry>,
        onChanged: () -> Unit
    ) {
        table.removeAllViews()
        if (entries.isEmpty()) {
            table.addView(TextView(this).apply {
                text = getString(R.string.catalogue_vide)
                setPadding(24, 24, 24, 24)
            })
            return
        }

        val unites = entries.map { it.unite }.distinct().sorted()
        val familles = entries.map { it.famille }.distinct().sorted()

        fun toggleGroup(itemsGroupe: List<Triple<String, String, String>>) {
            val toutSelectionne = itemsGroupe.isNotEmpty() && itemsGroupe.all { it in pdfExportSelection }
            if (toutSelectionne) pdfExportSelection.removeAll(itemsGroupe.toSet()) else pdfExportSelection.addAll(itemsGroupe)
            onChanged()
        }

        val headerRow = TableRow(this)
        headerRow.addView(catalogueHeaderCell(""))
        familles.forEach { famille ->
            headerRow.addView(catalogueHeaderCell(famille).apply {
                setOnClickListener {
                    toggleGroup(entries.filter { it.famille == famille }.map { Triple(it.unite, it.famille, it.item) })
                }
            })
        }
        table.addView(headerRow)

        unites.forEach { unite ->
            val row = TableRow(this)
            row.addView(catalogueHeaderCell(unite).apply {
                setOnClickListener {
                    toggleGroup(entries.filter { it.unite == unite }.map { Triple(it.unite, it.famille, it.item) })
                }
            })
            familles.forEach { famille ->
                val cell = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 8, 12, 8)
                }
                entries.filter { it.unite == unite && it.famille == famille }
                    .sortedBy { it.item }
                    .forEach { entry ->
                        val key = Triple(entry.unite, entry.famille, entry.item)
                        val selectionne = key in pdfExportSelection
                        cell.addView(TextView(this@MainActivity).apply {
                            text = entry.item
                            setTextColor(if (selectionne) ContextCompat.getColor(this@MainActivity, R.color.conforme) else Color.BLACK)
                            setTypeface(typeface, if (selectionne) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                            textSize = 13f
                            setPadding(6, 4, 6, 4)
                            setOnClickListener {
                                if (key in pdfExportSelection) pdfExportSelection.remove(key) else pdfExportSelection.add(key)
                                onChanged()
                            }
                        })
                    }
                row.addView(cell)
            }
            table.addView(row)
        }
    }

    /** Génère séquentiellement un PDF par item de [selection] (dossier files/exports). */
    private fun printSelectedItems(selection: List<Triple<String, String, String>>) {
        android.widget.Toast.makeText(this, R.string.pdf_export_en_cours, android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            var count = 0
            try {
                val exportManager = ExportManager(this@MainActivity, repo)
                for ((unite, famille, item) in selection) {
                    exportManager.exportPdf(unite, famille, item)
                    count++
                }
                android.widget.Toast.makeText(this@MainActivity, getString(R.string.pdf_export_termine, count), android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, getString(R.string.export_erreur, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Affiche la confirmation OUI (à gauche) / NON (à droite) puis, si OUI, ouvre le sélecteur
     * de fichier Excel. Android place toujours le bouton "négatif" à gauche et le bouton
     * "positif" à droite : on met donc OUI en négatif et NON en positif pour obtenir cet ordre.
     */
    private fun confirmImport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_titre)
            .setMessage(R.string.import_confirm_message)
            .setNegativeButton(R.string.dialog_oui) { _, _ -> pickExcelFile.launch(arrayOf("*/*")) }
            .setPositiveButton(R.string.dialog_non, null)
            .show()
    }

    /** Ouvre le fichier Excel choisi et demande quel onglet utiliser pour l'import. */
    private fun openWorkbookAndChooseSheet(uri: Uri) {
        lifecycleScope.launch {
            try {
                val workbook = repo.openExcelWorkbook(uri)
                val sheets = repo.listExcelSheets(workbook)
                if (sheets.isEmpty()) {
                    workbook.close()
                    android.widget.Toast.makeText(this@MainActivity, getString(R.string.import_erreur, "Aucun onglet trouvé dans ce fichier"), android.widget.Toast.LENGTH_LONG).show()
                    return@launch
                }
                var workbookClosed = false
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.import_choisir_onglet_titre)
                    .setItems(sheets.toTypedArray()) { _, which ->
                        workbookClosed = true
                        pendingWorkbook = workbook
                        pendingSheetName = sheets[which]
                        android.widget.Toast.makeText(this@MainActivity, R.string.import_choisir_dossier_schemas, android.widget.Toast.LENGTH_LONG).show()
                        pickSchemasFolder.launch(null)
                    }
                    .setOnCancelListener { if (!workbookClosed) workbook.close() }
                    .show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, getString(R.string.import_erreur, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Lit l'onglet [sheetName] du fichier choisi (catalogue de brides + Client/Lieu) et, si
     * [imagesTreeUri] est fourni, le dossier d'images de schémas associé, puis remplace les
     * données existantes.
     */
    private fun importFromExcel(workbook: ExcelImporter.OpenWorkbook, sheetName: String, imagesTreeUri: Uri?) {
        android.widget.Toast.makeText(this, R.string.import_en_cours, android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val result = repo.importFromExcelAndImages(workbook, sheetName, imagesTreeUri)

                // Le catalogue vient de changer : on repart d'une sélection vierge, la
                // collecte déjà active (observeUnites) se rechargera automatiquement.
                bridesJob?.cancel()
                schemaJob?.cancel()
                adapter.submit(emptyList())
                currentSchemaFile = null
                binding.imgSchema.visibility = View.GONE
                binding.emptySchemaLayout.visibility = View.VISIBLE
                selectedUnite = ""
                selectedFamille = ""
                selectedItem = ""
                observeUnites()

                val message = if (imagesTreeUri != null) {
                    getString(R.string.import_succes_avec_schemas, result.brideCount, result.schemaCount)
                } else {
                    getString(R.string.import_succes, result.brideCount)
                }
                android.widget.Toast.makeText(this@MainActivity, message, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, getString(R.string.import_erreur, e.message ?: ""), android.widget.Toast.LENGTH_LONG).show()
            } finally {
                workbook.close()
            }
        }
    }
}
