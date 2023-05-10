package com.gmail.hecarson3.velocimeter

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VelocimeterFragment : Fragment() {

    interface SettingsNavigator {
        fun onSettingsNavigate()
    }

    private lateinit var compassView: CompassView
    private lateinit var speedLabel: TextView
    private lateinit var settingsButton: FloatingActionButton
    private lateinit var debugLabel: TextView

    private lateinit var settingsNavigator: SettingsNavigator

    private lateinit var sensorManager: SensorManager
//    private var magnetometerSensor: Sensor? = null
    private var rotationVectorSensor: Sensor? = null
    private lateinit var locationClient: FusedLocationProviderClient

    private lateinit var requestFineLocationPermissionLauncher: ActivityResultLauncher<String>
    private var requestedLocationPermissions = false

//    private var deviceMagneticField = FloatArray(3)
    private val rotationVector = FloatArray(4)
    private var compassAngleDeg = 0f
    private val compassAngleAlpha = 0.0f
    private var velocityBearingDeg = 0f

    private lateinit var mainHandler: Handler
    private var count = 0

    override fun onAttach(context: Context) {
        super.onAttach(context)

        settingsNavigator = context as SettingsNavigator
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = requireContext().getSystemService(SensorManager::class.java)

//        val magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
//        if (magnetometerSensor == null) {
//            showErrorDialog("Unable to access magnetometer")
//        }
//        this.magnetometerSensor = magnetometerSensor
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
        debugLabel = view.findViewById(R.id.debugLabel)

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

//        if (accelerometerSensor != null && magnetometerSensor != null) {
//            sensorManager.registerListener(
//                accelerometerEventListener, accelerometerSensor,
//                SensorManager.SENSOR_DELAY_NORMAL, 1_000_000
//            )
//            sensorManager.registerListener(
//                magnetometerEventListener, magnetometerSensor,
//                SensorManager.SENSOR_DELAY_NORMAL, 1_000_000
//            )
//
//            Log.d(null, "registered sensors")
//        }
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

//        if (accelerometerSensor != null && magnetometerSensor != null) {
//            sensorManager.unregisterListener(accelerometerEventListener)
//            sensorManager.unregisterListener(magnetometerEventListener)
//
//            Log.d(null, "unregistered sensors")
//        }
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

//    private val magnetometerEventListener = object : SensorEventListener {
//        override fun onSensorChanged(sensorEvent: SensorEvent) {
//            for (i in 0..2)
//                deviceMagneticField[i] = sensorEvent.values[i]
//        }
//
//        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
//    }
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

            compassAngleDeg = compassAngleDeg * compassAngleAlpha +
                    (-orientation[0] * 180f / Math.PI.toFloat()) * (1 - compassAngleAlpha)
//            val velocityAngleDeg = velocityBearingDeg - orientation[0]
            val velocityAngleDeg = velocityBearingDeg + compassAngleDeg

            mainHandler.post {
                compassView.update(compassAngleDeg, velocityAngleDeg)
//                    debugLabel.text = "compass angle ${compassAngleDeg}\ncount ${count++}"
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
        var debugLabelText = "speed"
        if (location.hasSpeed())
            debugLabelText += " ${location.speed}"
        debugLabelText += "\n"

        if (location.hasBearing())
            velocityBearingDeg = location.bearing

        debugLabelText += "latitude ${location.latitude}\nlongitude ${location.longitude}"

        debugLabel.text = debugLabelText

        val speedNumStr = String.format("%.1f", location.speed * 2.23694)
        speedLabel.text = "$speedNumStr MPH"
    }

    private fun onSettingsButtonClick(view: View) {
        settingsNavigator.onSettingsNavigate()
    }

}