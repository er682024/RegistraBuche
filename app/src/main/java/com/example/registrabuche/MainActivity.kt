@file:Suppress("SpellCheckingInspection")

package com.example.registrabuche

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import android.annotation.SuppressLint

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var db: AppDatabase
    private lateinit var locationManager: LocationManager

    private val markerMap = mutableMapOf<Pair<Double, Double>, Marker>()
    private val viewModel: MainViewModel by viewModels()

    private var lastSaveTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().load(this, getSharedPreferences("osmdroid", MODE_PRIVATE))

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        db = AppDatabase.getDatabase(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        map = findViewById(R.id.map)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(15.0)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 🔴 Pulsante REC → evento ViewModel
        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            viewModel.onRegisterButtonPressed()
        }

        // 🟢 Osservatore dell’evento → chiama saveCurrentLocation()
        viewModel.saveLocationEvent.observe(this) { event ->
            event.getContentIfNotHandled()?.let {
                saveCurrentLocation()
            }
        }

        requestPermissions()
        observeBuche()
    }

    private fun observeBuche() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.bucaDao().getAllFlow().collectLatest { buche ->
                    updateMapMarkers(buche)
                }
            }
        }
    }

    private fun updateMapMarkers(buche: List<Buca>) {
        val currentKeys = buche.map { it.latitude to it.longitude }.toSet()

        val iterator = markerMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in currentKeys) {
                map.overlays.remove(entry.value)
                iterator.remove()
            }
        }

        for (buca in buche) {
            val key = buca.latitude to buca.longitude

            markerMap.getOrPut(key) {
                Marker(map).apply {
                    position = GeoPoint(buca.latitude, buca.longitude)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                    title = "Buca rilevata"
                    setOnMarkerClickListener { m, _ ->
                        showStatusDialog(m)
                        true
                    }
                    map.overlays.add(this)
                }
            }.also { marker ->
                marker.relatedObject = buca
            }
        }

        map.invalidate()
    }

    private fun showStatusDialog(marker: Marker) {
        val buca = marker.relatedObject as? Buca ?: return

        val isResolved = buca.timestamp_resolved != null
        val optionText = if (isResolved) "Segna come NON Risolta" else "Segna come Risolta"

        AlertDialog.Builder(this)
            .setTitle("Stato Buca")
            .setItems(arrayOf(optionText)) { _, _ ->
                lifecycleScope.launch {
                    val newTimestamp = if (isResolved) null else System.currentTimeMillis()
                    db.bucaDao().markAsResolved(buca.latitude, buca.longitude, newTimestamp)
                    Toast.makeText(this@MainActivity, "Stato aggiornato", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (permissions.any {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }) {
            ActivityCompat.requestPermissions(this, permissions, 1)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if ((event?.repeatCount ?: 0) > 0) return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                viewModel.onHardwareKeyPressed()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @SuppressLint("MissingPermission")
    private fun saveCurrentLocation() {
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < 1000) return
        lastSaveTime = now

        if (ActivityCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "Permessi GPS mancanti", Toast.LENGTH_SHORT).show()
            return
        }

        val location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

        if (location != null) {
            processLocation(location)
        } else {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                0L,
                0f,
                object : LocationListener {
                    override fun onLocationChanged(l: Location) {
                        locationManager.removeUpdates(this)
                        processLocation(l)
                    }

                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                },
                Looper.getMainLooper()
            )
        }
    }

    private fun processLocation(location: Location) {
        lifecycleScope.launch {
            db.bucaDao().saveOrUpdate(location.latitude, location.longitude)
            val point = GeoPoint(location.latitude, location.longitude)
            map.controller.animateTo(point)
        }
    }

    override fun onResume() {
        super.onResume()
        map.onResume()
    }

    override fun onPause() {
        super.onPause()
        map.onPause()
    }
}
