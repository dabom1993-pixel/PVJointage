package com.adf.pvjointage.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import coil.load
import com.adf.pvjointage.PvApp
import com.adf.pvjointage.R
import com.adf.pvjointage.data.ItemSchema
import com.adf.pvjointage.data.PvHeader
import com.adf.pvjointage.databinding.ActivityMainBinding
import com.adf.pvjointage.export.ExportManager
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.Locale

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

    private val pickSchemaImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) saveSchemaFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        binding.rvBrides.layoutManager = LinearLayoutManager(this)
        binding.rvBrides.adapter = adapter

        lifecycleScope.launch { repo.ensureSeedData(); observeHeader(); observeUnites() }

        binding.etDate.setOnClickListener { showDatePicker() }
        binding.btnAjouterSchema.setOnClickListener { pickSchemaImage.launch("image/*") }

        attachHeaderWatchers()
        observeBridesAndInspections()
    }

    private fun showDatePicker() {
        val cal = Calendar.getInstance()
        android.app.DatePickerDialog(this, { _, y, m, d ->
            val date = String.format(Locale.FRANCE, "%02d/%02d/%04d", d, m + 1, y)
            binding.etDate.setText(date)
            currentHeader = currentHeader.copy(date = date)
            saveHeader()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun attachHeaderWatchers() {
        binding.etClient.addTextChangedOnly { currentHeader = currentHeader.copy(client = it); saveHeader() }
        binding.etLieu.addTextChangedOnly { currentHeader = currentHeader.copy(lieu = it); saveHeader() }
        binding.etFaitPar.addTextChangedOnly { currentHeader = currentHeader.copy(faitPar = it); saveHeader() }
    }

    private fun com.google.android.material.textfield.TextInputEditText.addTextChangedOnly(onChanged: (String) -> Unit) {
        this.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { onChanged(s?.toString() ?: "") }
        })
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

    private fun observeUnites() {
        lifecycleScope.launch {
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
                        binding.imgSchema.load(File(schema.filePath))
                        binding.imgSchema.visibility = View.VISIBLE
                        binding.emptySchemaLayout.visibility = View.GONE
                    } else {
                        binding.imgSchema.visibility = View.GONE
                        binding.emptySchemaLayout.visibility = View.VISIBLE
                    }
                }
            }
        }
    }

    private fun saveSchemaFromUri(uri: Uri) {
        if (selectedUnite.isBlank() || selectedItem.isBlank()) return
        lifecycleScope.launch {
            try {
                val destDir = (getExternalFilesDir("schemas") ?: filesDir).apply { mkdirs() }
                val fileName = "schema_${selectedUnite}_${selectedFamille}_${selectedItem}.png"
                val destFile = File(destDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }
                repo.saveSchema(
                    ItemSchema(unite = selectedUnite, famille = selectedFamille, item = selectedItem, filePath = destFile.absolutePath)
                )
            } catch (e: Exception) {
                android.widget.Toast.makeText(this@MainActivity, "Impossible d'importer l'image", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
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
}
