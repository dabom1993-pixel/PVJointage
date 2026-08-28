package com.adf.pvjointage.ui

import android.app.Dialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.adf.pvjointage.PvApp
import com.adf.pvjointage.R
import com.adf.pvjointage.data.PvHeader
import com.adf.pvjointage.databinding.ActivityMainBinding
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

    private var pendingExcelUri: Uri? = null

    private val pickExcelFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            pendingExcelUri = uri
            android.widget.Toast.makeText(this, R.string.import_choisir_dossier_schemas, android.widget.Toast.LENGTH_LONG).show()
            pickSchemasFolder.launch(null)
        }
    }

    /** Dossier des images de schémas (un fichier par ITEM) : demandé juste après le fichier Excel. */
    private val pickSchemasFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri: Uri? ->
        val excelUri = pendingExcelUri
        pendingExcelUri = null
        if (excelUri != null) importFromExcel(excelUri, treeUri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.rvBrides.layoutManager = LinearLayoutManager(this)
        binding.rvBrides.adapter = adapter

        lifecycleScope.launch { repo.ensureSeedData(); observeHeader(); observeUnites() }

        binding.imgSchema.setOnTouchListener { _, event ->
            schemaGestureDetector.onTouchEvent(event)
            true
        }

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
                        if (binding.etFaitPar.text.toString() != header.faitPar) binding.etFaitPar.setText(header.faitPar)
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
                    populateSpinner(binding.spUnite, unites, selectedUnite) { chosen ->
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

    private fun observeFamilles() {
        lifecycleScope.launch {
            repo.getFamilles(selectedUnite).collect { familles ->
                populateSpinner(binding.spFamille, familles, selectedFamille) { chosen ->
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

    private fun observeItems() {
        lifecycleScope.launch {
            repo.getItems(selectedUnite, selectedFamille).collect { items ->
                populateSpinner(binding.spItem, items, selectedItem) { chosen ->
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

    private fun populateSpinner(spinner: android.widget.Spinner, values: List<String>, selected: String, onSelected: (String) -> Unit) {
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
                if (value != selected) onSelected(value)
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

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_import) {
            confirmImport()
            return true
        }
        if (item.itemId == R.id.action_export) {
            if (selectedUnite.isBlank() || selectedItem.isBlank()) return true
            val options = arrayOf("Export CSV (compatible Excel)", "Export PDF (avec photos)")
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.btn_export))
                .setItems(options) { _, which ->
                    lifecycleScope.launch {
                        val manager = ExportManager(this@MainActivity, repo)
                        val path = if (which == 0) {
                            manager.exportCsv(selectedUnite, selectedFamille, selectedItem)
                        } else {
                            manager.exportPdf(selectedUnite, selectedFamille, selectedItem)
                        }
                        android.widget.Toast.makeText(this@MainActivity, getString(R.string.export_done, path), android.widget.Toast.LENGTH_LONG).show()
                    }
                }
                .show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /** Affiche la confirmation OUI/NON puis, si OUI, ouvre le sélecteur de fichier Excel. */
    private fun confirmImport() {
        AlertDialog.Builder(this)
            .setTitle(R.string.import_confirm_titre)
            .setMessage(R.string.import_confirm_message)
            .setPositiveButton(R.string.dialog_oui) { _, _ -> pickExcelFile.launch(arrayOf("*/*")) }
            .setNegativeButton(R.string.dialog_non, null)
            .show()
    }

    /**
     * Lit l'onglet "1-Trame" du fichier choisi (catalogue de brides) et, si [imagesTreeUri] est
     * fourni, le dossier d'images de schémas associé, puis remplace les données existantes.
     */
    private fun importFromExcel(uri: Uri, imagesTreeUri: Uri?) {
        android.widget.Toast.makeText(this, R.string.import_en_cours, android.widget.Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val result = repo.importFromExcelAndImages(uri, imagesTreeUri)

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
            }
        }
    }
}
