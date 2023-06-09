package com.gmail.hecarson3.velocimeter

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class VelocimeterFragment : Fragment() {

    interface SettingsNavigator {
        fun onSettingsNavigate()
    }

    // Views
    private lateinit var compassView: CompassView
    private lateinit var speedLabel: TextView
    private lateinit var settingsButton: FloatingActionButton
    private lateinit var debugMagnetometerLabel: TextView
    private lateinit var debugRotationLabel: TextView
    private lateinit var debugLocationLabel: TextView

    // Preferences
    private lateinit var sharedPreferences: SharedPreferences
    private var showDebugInfo = false
    private var speedUnits = SpeedUnits.MPS

    // Navigation callbacks
    private lateinit var settingsNavigator: SettingsNavigator

    // Sensors
    private lateinit var sensorManager: SensorManager
    private var magnetometerSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null
    private lateinit var locationClient: FusedLocationProviderClient

    // Permissions
    private lateinit var requestFineLocationPermissionLauncher: ActivityResultLauncher<String>
    private var requestedLocationPermissions = false

    // Velocimeter data
    private var deviceMagneticField = FloatArray(3)
    private val rotationVector = FloatArray(4)
    private var velocityBearingDeg = 0f

    private lateinit var mainHandler: Handler
    private var count = 0

    override fun onAttach(context: Context) {
        super.onAttach(context)

        settingsNavigator = context as SettingsNavigator
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext())

        sensorManager = requireContext().getSystemService(SensorManager::class.java)

        val magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (magnetometerSensor == null) {
            showErrorDialog("Unable to access magnetometer")
        }
        this.magnetometerSensor = magnetometerSensor
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationVectorSensor == null) {
            showErrorDialog("Unable to access rotation sensor")
        }
        this.rotationVectorSensor = rotationVectorSensor

        locationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        requestFineLocationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(), this::onFineLocationPermissionRequestResult
        )

        mainHandler = Handler(Looper.getMainLooper())
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_velocimeter, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        compassView = view.findViewById(R.id.compassView)
        speedLabel = view.findViewById(R.id.speedLabel)
        settingsButton = view.findViewById(R.id.settingsButton)
        debugMagnetometerLabel = view.findViewById(R.id.debugMagnetometerLabel)
        debugRotationLabel = view.findViewById(R.id.debugRotationLabel)
        debugLocationLabel = view.findViewById(R.id.debugLocationLabel)

        settingsButton.setOnClickListener(this::onSettingsButtonClick)

        lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                loopUpdateCompass()
            }
        }
    }

    override fun onStart() {
        super.onStart()

        requestedLocationPermissions = false
    }

    override fun onResume() {
        super.onResume()

        showDebugInfo = sharedPreferences.getBoolean("debug_info", false)
        speedUnits = when (sharedPreferences.getString("speed_units", "mps")) {
            "mps" -> SpeedUnits.MPS
            "fps" -> SpeedUnits.FPS
            "kph" -> SpeedUnits.KPH
            "mph" -> SpeedUnits.MPH
            else -> SpeedUnits.MPS
        }

        val debugLabelVisibility = if (showDebugInfo) View.VISIBLE else View.GONE
        debugMagnetometerLabel.visibility = debugLabelVisibility
        debugRotationLabel.visibility = debugLabelVisibility
        debugLocationLabel.visibility = debugLabelVisibility

        if (magnetometerSensor != null) {
            sensorManager.registerListener(
                magnetometerEventListener, magnetometerSensor, 30_000
            )
        }
        if (rotationVectorSensor != null) {
            sensorManager.registerListener(
                rotationSensorEventListener, rotationVectorSensor, 30_000
            )
        }

        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED) {
            if (!requestedLocationPermissions) {
                requestFineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                requestedLocationPermissions = true
            }
        }
        else
            requestLocationUpdates()
    }

    override fun onPause() {
        super.onPause()

        sensorManager.unregisterListener(magnetometerEventListener)
        sensorManager.unregisterListener(rotationSensorEventListener)

        locationClient.removeLocationUpdates(this::onCurrentLocationUpdate)
    }

    private fun showErrorDialog(message: String) {
        val alertDialog = AlertDialog.Builder(requireContext())
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .create()
        alertDialog.show()
    }

    private val magnetometerEventListener = object : SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            var magneticFieldMag = 0f

            for (i in 0..2) {
                deviceMagneticField[i] = sensorEvent.values[i]
                magneticFieldMag += deviceMagneticField[i] * deviceMagneticField[i]
            }
            magneticFieldMag = sqrt(magneticFieldMag)

            if (showDebugInfo)
                debugMagnetometerLabel.text = "magnetic field magnitude $magneticFieldMag"
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }
    private val rotationSensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            for (i in 0..3)
                rotationVector[i] = sensorEvent.values[i]
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    private suspend fun loopUpdateCompass() = withContext(Dispatchers.Default) {
        while (true) {
            val R = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(R, rotationVector)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)
            val azimuthDeg = orientation[0] * 180f / Math.PI.toFloat()

            val compassAngleDeg = -azimuthDeg
            val velocityAngleDeg = velocityBearingDeg - azimuthDeg

            mainHandler.post {
                compassView.update(compassAngleDeg, velocityAngleDeg)

                if (showDebugInfo)
                    debugRotationLabel.text = "device azimuth $azimuthDeg"
            }

            delay(30)
        }
    }

    private fun onFineLocationPermissionRequestResult(granted: Boolean) {
        if (granted)
            requestLocationUpdates()
        else
            showErrorDialog("The fine/precise location permission is needed to accurately calculate speed")
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .build()
        locationClient.requestLocationUpdates(
            locationRequest, this::onCurrentLocationUpdate, Looper.getMainLooper()
        )
    }

    private fun onCurrentLocationUpdate(location: Location) {
        val speedNumStr = String.format("%.1f", location.speed * 2.23694)
        speedLabel.text = "$speedNumStr MPH"

        if (location.hasBearing())
            velocityBearingDeg = location.bearing

        if (showDebugInfo) {
            var debugLocationText = "latitude ${location.latitude}\nlongitude ${location.longitude}\n"

            debugLocationText += "speed"
            if (location.hasSpeed())
                debugLocationText += " ${location.speed}"
            debugLocationText += "\n"

            debugLocationText += "velocity bearing"
            if (location.hasBearing())
                debugLocationText += " ${location.bearing}"

            debugLocationLabel.text = debugLocationText
        }
    }

    private fun onSettingsButtonClick(view: View) {
        settingsNavigator.onSettingsNavigate()
    }

}