package com.gmail.hecarson3.velocimeter

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class VelocimeterFragment : Fragment() {

    private lateinit var sensorManager: SensorManager
    private var accelerometerSensor: Sensor? = null
    private var magnetometerSensor: Sensor? = null
    private lateinit var locationClient: FusedLocationProviderClient

    private lateinit var requestFineLocationPermissionLauncher: ActivityResultLauncher<String>
    private var requestedLocationPermissions = false;

    private lateinit var compassView: CompassView
    private lateinit var debugLabel: TextView

    private var deviceGravity = FloatArray(3)
    private var deviceMagneticField = FloatArray(3)
    private val sensorAlpha = 0.8f
    private var compassAngleDeg = 0f
    private val compassAngleAlpha = 0.5f
    private var velocityBearing = 0f

    private lateinit var mainHandler: Handler
    private var count = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = requireContext().getSystemService(SensorManager::class.java)

        val accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accelerometerSensor == null) {
            showErrorDialog("Unable to access accelerometer")
        }
        if (magnetometerSensor == null) {
            showErrorDialog("Unable to access magnetometer")
        }
        this.accelerometerSensor = accelerometerSensor
        this.magnetometerSensor = magnetometerSensor

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
        debugLabel = view.findViewById(R.id.debugLabel)

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

        if (accelerometerSensor != null && magnetometerSensor != null) {
            sensorManager.registerListener(
                accelerometerEventListener, accelerometerSensor,
                SensorManager.SENSOR_DELAY_NORMAL, 1_000_000
            )
            sensorManager.registerListener(
                magnetometerEventListener, magnetometerSensor,
                SensorManager.SENSOR_DELAY_NORMAL, 1_000_000
            )

            Log.d(null, "registered sensors")
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

        if (accelerometerSensor != null && magnetometerSensor != null) {
            sensorManager.unregisterListener(accelerometerEventListener)
            sensorManager.unregisterListener(magnetometerEventListener)

            Log.d(null, "unregistered sensors")
        }

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

    private val accelerometerEventListener = object : SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            for (i in 0..2)
                deviceGravity[i] = deviceGravity[i] * sensorAlpha + sensorEvent.values[i] * (1 - sensorAlpha)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val magnetometerEventListener = object : SensorEventListener {
        override fun onSensorChanged(sensorEvent: SensorEvent) {
            for (i in 0..2)
                deviceMagneticField[i] = deviceMagneticField[i] * sensorAlpha + sensorEvent.values[i] * (1 - sensorAlpha)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private suspend fun loopUpdateCompass() = withContext(Dispatchers.Default) {
        while (true) {
            val R = FloatArray(9)
            val I = FloatArray(9)
            val getRotationMatrixSuccess =
                SensorManager.getRotationMatrix(R, I, deviceGravity, deviceMagneticField)
            val orientation = FloatArray(3)
            SensorManager.getOrientation(R, orientation)

            if (getRotationMatrixSuccess) {
                compassAngleDeg = compassAngleDeg * compassAngleAlpha +
                        (-orientation[0] * 180f / Math.PI.toFloat()) * (1 - compassAngleAlpha)

                mainHandler.post {
                    compassView.update(compassAngleDeg, 0f)
//                    debugLabel.text = "compass angle ${compassAngleDeg}\ncount ${count++}"
                }
            }

            delay(30)
        }
    }

    private fun onFineLocationPermissionRequestResult(granted: Boolean) {
        if (granted)
            requestLocationUpdates()
        else
            showErrorDialog("The fine location permission is needed to accurately calculate speed")
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
        debugLabel.text = "speed"

        if (location.hasSpeed())
            debugLabel.text = debugLabel.text.toString() + " ${location.speed}\n"

        if (location.hasBearing())
            velocityBearing = location.bearing

        debugLabel.text = debugLabel.text.toString() + "latitude ${location.latitude}\nlongitude${location.longitude}"
    }

}