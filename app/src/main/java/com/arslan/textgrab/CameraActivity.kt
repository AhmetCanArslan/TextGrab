package com.arslan.textgrab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.arslan.textgrab.databinding.ActivityCameraBinding
import java.io.File

/** In-app viewfinder: saves one photo to [photoFile] and finishes with RESULT_OK. */
class CameraActivity : AppCompatActivity() {

    companion object {
        fun photoFile(context: Context) = File(context.cacheDir, "camera/photo.jpg")
    }

    private lateinit var binding: ActivityCameraBinding
    private var controller: LifecycleCameraController? = null

    private val requestPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            SystemBarStyle.dark(Color.TRANSPARENT),
            SystemBarStyle.dark(Color.TRANSPARENT)
        )
        binding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.controls) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        binding.btnClose.setOnClickListener { finish() }
        binding.btnShutter.setOnClickListener { takePhoto() }
        binding.btnTorch.isVisible =
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)
        binding.btnTorch.addOnCheckedChangeListener { _, checked ->
            controller?.enableTorch(checked)
        }

        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) startCamera()
        else if (savedInstanceState == null) requestPermission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val controller = LifecycleCameraController(this).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        }
        controller.initializationFuture.addListener({
            runCatching {
                if (!controller.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    controller.cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                }
            }
        }, ContextCompat.getMainExecutor(this))
        binding.preview.controller = controller
        controller.bindToLifecycle(this)
        this.controller = controller
    }

    private fun takePhoto() {
        val controller = controller ?: return
        val file = photoFile(this)
        file.parentFile?.mkdirs()
        binding.btnShutter.isEnabled = false
        controller.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    setResult(RESULT_OK)
                    finish()
                }

                override fun onError(e: ImageCaptureException) {
                    android.util.Log.e("TextGrab", "Photo capture failed", e)
                    file.delete()
                    binding.btnShutter.isEnabled = true
                    Toast.makeText(
                        this@CameraActivity, R.string.camera_failed, Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }
}
