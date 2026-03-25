package com.example.registrabuche

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapsInitializer
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var db: AppDatabase
    private var googleMap: GoogleMap? = null

    // Keep track of markers to avoid map.clear() which can cause flickering and native crashes
    private val markerMap = mutableMapOf<Pair<Double, Double>, Marker>()
    private var lastSaveTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Force Legacy Renderer to avoid potential native crashes (SIGSEGV) on some devices
        MapsInitializer.initialize(applicationContext, MapsInitializer.Renderer.LEGACY) {
            // Renderer initialized
        }
        
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        
        db = AppDatabase.getDatabase(this)
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        findViewById<Button>(R.id.btnRegister).setOnClickListener {
            saveCurrentLocation()
        }

        requestPermissions()
        observeBuche()
    }

    private fun observeBuche() {
        lifecycleScope.launch {
            // Update UI only when activity is visible
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                db.bucaDao().getAllFlow().collectLatest { buche ->
                    updateMapMarkers(buche)
                }
            }
        }
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            googleMap?.isMyLocationEnabled = true
        }

        googleMap?.setOnMarkerClickListener { marker ->
            showStatusDialog(marker)
            true
        }
    }

    private fun showStatusDialog(marker: Marker) {
        val buca = marker.tag as? Buca ?: return
        
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

    private fun updateMapMarkers(buche: List<Buca>) {
        val map = googleMap ?: return

        val currentTime = System.currentTimeMillis()
        val thirtyDaysInMillis = 30L * 24 * 60 * 60 * 1000

        // Remove markers that are no longer in the database
        val currentKeys = buche.map { it.latitude to it.longitude }.toSet()
        val iterator = markerMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in currentKeys) {
                entry.value.remove()
                iterator.remove()
            }
        }

        // Add or update markers incrementally
        for (buca in buche) {
            val key = buca.latitude to buca.longitude
            val isOld = (currentTime - buca.timestamp_insert) > thirtyDaysInMillis

            val color = when {
                buca.timestamp_resolved != null -> BitmapDescriptorFactory.HUE_GREEN
                buca.timestamp_resolved == null && isOld -> BitmapDescriptorFactory.HUE_VIOLET
                buca.timestamp_insert < buca.timestamp_last -> BitmapDescriptorFactory.HUE_YELLOW
                else -> BitmapDescriptorFactory.HUE_RED
            }

            val existingMarker = markerMap[key]
            if (existingMarker != null) {
                existingMarker.setIcon(BitmapDescriptorFactory.defaultMarker(color))
                existingMarker.tag = buca
            } else {
                val pos = LatLng(buca.latitude, buca.longitude)
                val marker = map.addMarker(
                    MarkerOptions()
                        .position(pos)
                        .icon(BitmapDescriptorFactory.defaultMarker(color))
                        .title("Buca rilevata")
                )
                marker?.tag = buca
                if (marker != null) markerMap[key] = marker
            }
        }
    }

    private fun requestPermissions() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Prevent repeated actions on long key press
        if (event?.repeatCount ?: 0 > 0) return super.onKeyDown(keyCode, event)

        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP, 
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER -> {
                saveCurrentLocation()
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun saveCurrentLocation() {
        // Throttling to prevent multiple records for the same event (max 1 per second)
        val now = System.currentTimeMillis()
        if (now - lastSaveTime < 1000) return
        lastSaveTime = now

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permessi GPS mancanti", Toast.LENGTH_SHORT).show()
            return
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
            .addOnSuccessListener { location ->
                if (location != null) {
                    lifecycleScope.launch {
                        db.bucaDao().saveOrUpdate(location.latitude, location.longitude)
                        
                        googleMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(
                            LatLng(location.latitude, location.longitude), 15f
                        ))
                    }
                }
            }
    }
}