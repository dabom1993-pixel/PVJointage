package com.adf.pvjointage.ui

import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.adf.pvjointage.PvApp
import com.adf.pvjointage.data.Photo
import com.adf.pvjointage.databinding.ActivityPhotosBinding
import kotlinx.coroutines.launch
import java.io.File

/**
 * Galerie de photos rattachées à un ITEM. Correspond à l'onglet "1-Plan" du fichier Excel
 * (colonnes Photo 1..N indexées par Unité / Famille / Item).
 */
class PhotosActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPhotosBinding
    private val repo by lazy { (application as PvApp).repository }

    private lateinit var unite: String
    private lateinit var famille: String
    private lateinit var item: String
    private var pendingUri: Uri? = null

    private val adapter = PhotoAdapter { photo -> lifecycleScope.launch { repo.deletePhoto(photo) } }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && pendingUri != null) {
            lifecycleScope.launch {
                repo.addPhoto(Photo(unite = unite, famille = famille, item = item, filePath = pendingUri.toString()))
            }
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPhotosBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        unite = intent.getStringExtra("unite") ?: ""
        famille = intent.getStringExtra("famille") ?: ""
        item = intent.getStringExtra("item") ?: ""

        binding.tvItemLabel.text = "$unite  •  $famille  •  ITEM $item"
        binding.rvPhotos.layoutManager = GridLayoutManager(this, 3)
        binding.rvPhotos.adapter = adapter

        binding.btnAjouterPhoto.setOnClickListener { requestCameraAndLaunch() }

        lifecycleScope.launch {
            repo.getPhotosForItem(unite, famille, item).collect { photos ->
                adapter.submit(photos)
                binding.tvAucunePhoto.visibility = if (photos.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            }
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
        val fileName = "PLAN_${item}_${System.currentTimeMillis()}.jpg"
        val photosDir = (getExternalFilesDir("photos") ?: filesDir).apply { mkdirs() }
        val file = File(photosDir, fileName)
        val uri = androidx.core.content.FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        pendingUri = uri
        takePicture.launch(uri)
    }
}
