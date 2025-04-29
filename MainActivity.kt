package com.yujin.capture

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.yujin.capture.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ActivityCompat.requestPermissions(
            this@MainActivity,
            arrayOf(
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA
            ),
            123
        )

        binding.btnGetCameraId.setOnClickListener {
            val intent = Intent(this, SelectActivity::class.java)
            startActivity(intent)
        }
        binding.btnPreview.setOnClickListener {
            val intent = Intent(this, CameraActivity::class.java)
            startActivity(intent)
        }
        binding.btnTakeImageHdr.setOnClickListener {
            val intent = Intent(this, CameraImageHDRActivity::class.java)
            startActivity(intent)
        }
        binding.btnTakeVideoHdr.setOnClickListener {
            val intent = Intent(this, CameraHDRActivity::class.java)
            startActivity(intent)
        }
        binding.btnTakeMultiExposure.setOnClickListener {
            val intent = Intent(this, CameraMultiExpoActivity::class.java)
            startActivity(intent)
        }
        binding.btnCaptureVideo.setOnClickListener {
            val intent = Intent(this, CameraActivity3::class.java)
            startActivity(intent)
        }
    }
}