package com.adf.pvjointage.ui

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.adf.pvjointage.PvApp
import com.adf.pvjointage.R
import com.adf.pvjointage.data.BrideCatalog
import com.adf.pvjointage.data.InspectionResult
import com.adf.pvjointage.data.Photo
import com.adf.pvjointage.databinding.ActivityChampBinding
import com.adf.pvjointage.model.ConformiteCalculator
import com.adf.pvjointage.model.Conformite
import com.adf.pvjointage.model.Etat
import kotlinx.coroutines.launch
import java.io.File

class ChampActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChampBinding
    private val repo by lazy { (application as PvApp).repository }

    private lateinit var unite: String
    private lateinit var famille: String
    private lateinit var item: String
    private lateinit var rep: String

    private var bride: BrideCatalog? = null
    private var pendingPhotoUri: Uri? = null

    private val photoAdapter = PhotoAdapter { photo ->
        lifecycleScope.launch { repo.deletePhoto(photo) }
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingPhotoUri != null) {
            lifecycleScope.launch {
                repo.addPhoto(Photo(unite = unite, famille = famille, item = item, rep = rep, filePath = pendingPhotoUri.toString()))
            }
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChampBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)
        binding.toolbar.setNavigationOnClickListener { finish() }

        unite = intent.getStringExtra("unite") ?: ""
        famille = intent.getStringExtra("famille") ?: ""
        item = intent.getStringExtra("item") ?: ""
        rep = intent.getStringExtra("rep") ?: ""

        binding.tvContexte.text = "$unite  •  $famille  •  ITEM $item  •  Bride $rep"

        setupLabels()
        loadBrideRef()
        loadInspection()
        setupPhotos()

        binding.btnPrendrePhoto.setOnClickListener { requestCameraAndLaunch() }
    }

    private fun setupLabels() {
        binding.selEtiMiseSerree.setLabel(getString(R.string.etiquette_mise_serree))
        binding.selEtiMiseSerree.setAutreVisible(false)
        binding.selEtiNomDate.setLabel(getString(R.string.etiquette_nom_date))
        binding.selEtiNomDate.setAutreVisible(false)

        binding.selJointMatiere.setLabel(getString(R.string.joint_matiere_conforme))
        binding.selJointDimension.setLabel(getString(R.string.joint_dimension_centrage))
        binding.selJointAspect.setLabel(getString(R.string.joint_aspect_neuf))
        binding.selJointAspect.setAutreVisible(false)

        binding.selBoulonNeuves.setLabel(getString(R.string.boulon_neuves))
        binding.selBoulonRondelles.setLabel(getString(R.string.boulon_rondelles))
        binding.selBoulonEquilibrage.setLabel(getString(R.string.boulon_equilibrage))
        binding.selBoulonEquilibrage.setAutreVisible(false)
        binding.selBoulonGraissage.setLabel(getString(R.string.boulon_graissage))
        binding.selBoulonGraissage.setAutreVisible(false)
        binding.selBoulonLongueur.setLabel(getString(R.string.boulon_longueur_diametre))
        binding.selBoulonLongueur.setAutreVisible(false)
        binding.selBoulonMatiere.setLabel(getString(R.string.boulon_matiere))

        binding.selAssemblageParallelisme.setLabel(getString(R.string.assemblage_parallelisme))
        binding.selAssemblageParallelisme.setAutreVisible(false)
        binding.selAssemblageExcentration.setLabel(getString(R.string.assemblage_excentration))
        binding.selAssemblageExcentration.setAutreVisible(false)

        // Chaque changement de statut est immédiatement enregistré (plus de bouton "Enregistrer").
        val listener: (Etat) -> Unit = { refreshStatuts(); saveInspection() }
        listOf(
            binding.selEtiMiseSerree, binding.selEtiNomDate,
            binding.selJointMatiere, binding.selJointDimension, binding.selJointAspect,
            binding.selBoulonNeuves, binding.selBoulonRondelles, binding.selBoulonEquilibrage,
            binding.selBoulonGraissage, binding.selBoulonLongueur, binding.selBoulonMatiere,
            binding.selAssemblageParallelisme, binding.selAssemblageExcentration
        ).forEach { it.onEtatChanged = listener }
    }

    private fun loadBrideRef() {
        lifecycleScope.launch {
            repo.getBrides(unite, famille, item).collect { list ->
                val b = list.firstOrNull { it.rep == rep } ?: return@collect
                bride = b
                binding.tvJointRef.text = "DN ${b.dn} — PN ${b.pn} — Matière joint : ${b.matiereJoint} — Rondelle : ${b.rondelle}"
                binding.tvBoulonRef.text = "Matière boulonnerie : ${b.matiereBoulon}"
            }
        }
    }

    private fun loadInspection() {
        lifecycleScope.launch {
            val insp = repo.getInspectionForBride(unite, famille, item, rep)
            if (insp != null) {
                binding.selEtiMiseSerree.setEtat(Etat.fromCode(insp.etiMiseSerree), notify = false)
                binding.selEtiNomDate.setEtat(Etat.fromCode(insp.etiNomDateLisible), notify = false)
                binding.selJointMatiere.setEtat(Etat.fromCode(insp.jointMatiereConforme), notify = false)
                binding.selJointDimension.setEtat(Etat.fromCode(insp.jointDimensionCentrage), notify = false)
                binding.selJointAspect.setEtat(Etat.fromCode(insp.jointAspectNeuf), notify = false)
                binding.selBoulonNeuves.setEtat(Etat.fromCode(insp.boulonNeuves), notify = false)
                binding.selBoulonRondelles.setEtat(Etat.fromCode(insp.boulonRondelles), notify = false)
                binding.selBoulonEquilibrage.setEtat(Etat.fromCode(insp.boulonEquilibrage), notify = false)
                binding.selBoulonGraissage.setEtat(Etat.fromCode(insp.boulonGraissage), notify = false)
                binding.selBoulonLongueur.setEtat(Etat.fromCode(insp.boulonLongueurDiametre), notify = false)
                binding.selBoulonMatiere.setEtat(Etat.fromCode(insp.boulonMatiere), notify = false)
                binding.selAssemblageParallelisme.setEtat(Etat.fromCode(insp.assemblageParallelisme), notify = false)
                binding.selAssemblageExcentration.setEtat(Etat.fromCode(insp.assemblageExcentration), notify = false)
            }
            refreshStatuts()
        }
    }

    private fun refreshStatuts() {
        val etiquette = ConformiteCalculator.etiquette(binding.selEtiMiseSerree.etat, binding.selEtiNomDate.etat)
        val joint = ConformiteCalculator.joint(binding.selJointMatiere.etat, binding.selJointDimension.etat, binding.selJointAspect.etat)
        val boulonnerie = ConformiteCalculator.boulonnerie(
            binding.selBoulonNeuves.etat, binding.selBoulonRondelles.etat, binding.selBoulonEquilibrage.etat,
            binding.selBoulonGraissage.etat, binding.selBoulonLongueur.etat, binding.selBoulonMatiere.etat
        )
        val assemblage = ConformiteCalculator.assemblage(binding.selAssemblageParallelisme.etat, binding.selAssemblageExcentration.etat)

        setStatutText(binding.tvStatutEtiquette, etiquette)
        setStatutText(binding.tvStatutJoint, joint)
        setStatutText(binding.tvStatutBoulonnerie, boulonnerie)
        setStatutText(binding.tvStatutAssemblage, assemblage)
    }

    private fun setStatutText(tv: android.widget.TextView, c: Conformite) {
        val (color, text) = when (c) {
            Conformite.CONFORME -> R.color.conforme to getString(R.string.statut_conforme)
            Conformite.NON_CONFORME -> R.color.non_conforme to getString(R.string.statut_non_conforme)
            Conformite.EN_ATTENTE -> R.color.en_attente to getString(R.string.statut_attente)
        }
        tv.setTextColor(ContextCompat.getColor(this, color))
        tv.text = text
    }

    private fun setupPhotos() {
        binding.rvPhotos.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPhotos.adapter = photoAdapter
        lifecycleScope.launch {
            repo.getPhotosForBride(unite, famille, item, rep).collect { photos -> photoAdapter.submit(photos) }
        }
    }

    private fun requestCameraAndLaunch() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermission.launch(android.Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val fileName = "PV_${item}_${System.currentTimeMillis()}.jpg"
        val photosDir = (getExternalFilesDir("photos") ?: filesDir).apply { mkdirs() }
        val file = File(photosDir, fileName)
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingPhotoUri = uri
        takePicture.launch(uri)
    }

    private fun saveInspection() {
        val result = InspectionResult(
            unite = unite, famille = famille, item = item, rep = rep,
            etiMiseSerree = binding.selEtiMiseSerree.etat.code,
            etiNomDateLisible = binding.selEtiNomDate.etat.code,
            jointMatiereConforme = binding.selJointMatiere.etat.code,
            jointDimensionCentrage = binding.selJointDimension.etat.code,
            jointAspectNeuf = binding.selJointAspect.etat.code,
            boulonNeuves = binding.selBoulonNeuves.etat.code,
            boulonRondelles = binding.selBoulonRondelles.etat.code,
            boulonEquilibrage = binding.selBoulonEquilibrage.etat.code,
            boulonGraissage = binding.selBoulonGraissage.etat.code,
            boulonLongueurDiametre = binding.selBoulonLongueur.etat.code,
            boulonMatiere = binding.selBoulonMatiere.etat.code,
            assemblageParallelisme = binding.selAssemblageParallelisme.etat.code,
            assemblageExcentration = binding.selAssemblageExcentration.etat.code
        )
        lifecycleScope.launch { repo.saveInspection(result) }
    }
}
