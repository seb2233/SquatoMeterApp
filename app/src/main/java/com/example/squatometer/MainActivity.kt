package com.example.squatometer

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.File
import java.util.UUID

class MainActivity : AppCompatActivity() {

    // UI stuff like buttons and live view of recorded data
    private lateinit var tvStatus: TextView
    private lateinit var tvLiveData: TextView
    private lateinit var btnScan: Button
    private lateinit var btnRecord: Button
    private lateinit var btnStop: Button
    private lateinit var btnReset: Button

    //state flags to signal where the app is at; like pressing the start button and waiting for the ble to connect
    private var pendingStart = false
    private var waitingForFlush = false

    //last sample
    private var lastSampleTime = 0L

    // ble objects:
    // ble connection
    private var bluetoothGatt: BluetoothGatt? = null

    //characteristic to read and write data through
    private var measurementChar: BluetoothGattCharacteristic? = null

    //stores search results after scanning for bluetooth devices
    private var scanCallback: ScanCallback? = null

    //ble identifiers for ble services of the esp32
    private val CHAR_UUID = UUID.fromString("d8d02583-8540-4411-8b27-deec23578ca3")
    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")



    // received data that will then be stored as a csv file
    private val allSamples = mutableListOf<List<Any>>()


    // for android permissions, arbitrary number/code however
    private val PERMISSION_REQUEST_CODE = 1001


    //create the ui
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus   = findViewById(R.id.tvStatus)
        tvLiveData = findViewById(R.id.tvLiveData)
        btnScan    = findViewById(R.id.btnScan)
        btnRecord  = findViewById(R.id.btnRecord)
        btnStop    = findViewById(R.id.btnStop)
        btnReset   = findViewById(R.id.btnReset)

        btnScan.setOnClickListener   { checkPermissionsScan() }
        btnRecord.setOnClickListener { startRecording() }
        btnStop.setOnClickListener   { stopRecording() }
        btnReset.setOnClickListener  { resetDevice() }
    }


    // enable set of permissions for ble activation
    private val reqPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }



    //this is for allowing permissions to use bluetooth and stuff for the app
    private fun checkPermissionsScan() {
        val missing = reqPermissions.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            startScan()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    //check for result of the user for permissions(pop up for granting permissions)
    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startScan()
        } else {
            setStatus("Permissions denied — cannot use BLE")
        }
    }

    // actual scanning
    private fun startScan() {
        //bluetooth starts scanning and if bluetooth is not enable it will stop at this if statement
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner

        if (scanner == null) {
            setStatus("Bluetooth not available")
            return
        }


        //while scanning it disables the button on the screen for scanning
        btnScan.isEnabled = false


       //this makes it look specifically for a device called Squato-Meter
        val callback = object : ScanCallback() {

            override fun onScanResult(callbackType: Int, result: ScanResult) {

                val name = try { result.device.name }
                            catch (e: SecurityException) { return }

                if (name == "Squato-Meter") {
                    stopScan()
                    setStatus("Found Squato-Meter")
                    connectToDevice(result.device)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                setStatus("Scan failed $errorCode")

                btnScan.isEnabled = true
            }
        }


        // wait for the application to find the Squato-Meter and start scanning
        scanCallback = callback
        try {
            scanner.startScan(callback)
        } catch (e: SecurityException) {

            setStatus("Scan permission denied: ${e.message}")

            btnScan.isEnabled = true

            return
        }

        // auto stop after 5 seconds
        Handler(Looper.getMainLooper()).postDelayed({
            stopScan()
            if (bluetoothGatt == null) {
                setStatus("Squato-Meter not found")
                btnScan.isEnabled = true
            }
        }, 5000)
    }

    //stop scan after scanning and finding or not finding the device
    private fun stopScan() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val scanner = bluetoothManager.adapter?.bluetoothLeScanner
        try {
            scanCallback?.let { scanner?.stopScan(it) }
        } catch (e: SecurityException) {

        }
        scanCallback = null
    }




    //connecting to Squato-Meter after scanning
    private fun connectToDevice(device: BluetoothDevice) {
        val gattCallback = object : BluetoothGattCallback() {


            //starts whenever connection state changes like disconnection
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        setStatus("Connected! Discovering services...")
                        try {
                            gatt.discoverServices()
                        } catch (e: SecurityException) {
                            setStatus("Permission denied during service discovery")
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        setStatus("Disconnected")
                        bluetoothGatt = null
                        measurementChar = null
                        runOnUiThread {
                            btnRecord.isEnabled = false
                            btnStop.isEnabled   = false
                            btnReset.isEnabled  = false
                            btnScan.isEnabled   = true
                        }
                    }
                }
            }


            // when connection was established it will get the ble services that Squato-Meter offers like UUID
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    measurementChar = gatt.services
                        .flatMap { it.characteristics }
                        .find { it.uuid == CHAR_UUID }

                    if (measurementChar != null) {
                        try {
                            gatt.requestMtu(512)
                        } catch (e: SecurityException) {
                            setStatus("Permission denied requesting MTU")
                        }
                    } else {
                        setStatus("Characteristic not found!")
                    }
                }
            }


            //after the connection is established then activate the UI buttons for record and reset
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    setStatus("Ready ✓ (MTU: $mtu)")
                } else {
                    setStatus("MTU request failed, data may be truncated")
                }
                runOnUiThread {
                    btnRecord.isEnabled = true
                    btnReset.isEnabled  = true
                }
            }



            //starts every time the ESP32 sends a notification
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                val line = characteristic.value?.let { String(it).trim() } ?: return
                handleNotification(line)
            }


            //starts when a command like start or stop recording is given
            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    setStatus("Write failed: $status")
                }
            }




            // Fires AFTER descriptor write completes — only then will be able to send start recording command
            override fun onDescriptorWrite( gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS && pendingStart) {
                    pendingStart = false
                    writeCommand("START")
                }
            }
        }

        try {
            bluetoothGatt = device.connectGatt(this, false, gattCallback)
        } catch (e: SecurityException) {
            setStatus("Connect permission failed ${e.message}")
        }
    }

    //enable ble notifications to receive each sample basically
    private fun enableNotifications(characteristic: BluetoothGattCharacteristic) {
        val gatt = bluetoothGatt ?: return
        try {
            gatt.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD_UUID)
            descriptor?.let {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        } catch (e: SecurityException) {
            setStatus("Permission denied enabling notifications: ${e.message}")
        }
    }

    //called everytime a sample arrives
    private fun handleNotification(line: String) {
        lastSampleTime = System.currentTimeMillis()

        //split data by ",", has to have 10 values per sample
        val parts = line.trim().split(",")

        //if formating is not done properly this will show on the phone screen
        if (parts.size != 10) {
            runOnUiThread { tvLiveData.append("Bad format: $line\n") }
            return
        }

        //formating each data point in the sample
        try {
            val timestamp = parts[0].toDouble()
            val distance  = parts[1].toInt()
            val roll      = parts[2].toDouble()
            val pitch     = parts[3].toDouble()
            val accX      = parts[4].toDouble()
            val accY      = parts[5].toDouble()
            val accZ      = parts[6].toDouble()
            val gyroRoll  = parts[7].toDouble()
            val gyroPitch = parts[8].toDouble()
            val gyroYaw   = parts[9].toDouble()


            //start saving the samples after the stop recording button was pressed
            if (waitingForFlush) {
                allSamples.add(listOf(
                    timestamp, distance, roll, pitch,
                    accX, accY, accZ, gyroRoll, gyroPitch, gyroYaw
                ))
            }


            //shows data on screen while recording only and has a delay so live data can be read more easily
            if (!waitingForFlush) {
                val display =
                    "T:${timestamp}s  Dist:${distance}mm\n" +
                            "Roll:$roll  Pitch:$pitch\n" +
                            "Acc:($accX, $accY, $accZ)\n" +
                            "Gyro:($gyroRoll, $gyroPitch, $gyroYaw)\n" +
                            "─────────────────────\n"
                Handler(Looper.getMainLooper()).postDelayed({
                    tvLiveData.append(display)
                    val scrollView = tvLiveData.parent as? android.widget.ScrollView
                    scrollView?.post { scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
                }, 700)
            }

        } catch (e: Exception) {
            runOnUiThread { tvLiveData.append("Parse error: ${e.message}\n") }
        }
    }






    //fucntion to send commands like start stop or reset
    private fun writeCommand(command: String) {
        val char = measurementChar ?: return
        try {
            char.value = command.toByteArray()
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(char)
        } catch (e: SecurityException) {
            setStatus("Permission denied writing '$command': ${e.message}")
        }
    }




    //start the recording
    private fun startRecording() {
        val char = measurementChar ?: return
        pendingStart = true

        //make sure notifications are enabled
        enableNotifications(char)
        //buttons on ui enable and disable
        runOnUiThread {
            btnRecord.isEnabled = false
            btnStop.isEnabled   = true
            tvLiveData.text     = ""
            setStatus("Recording...")
        }
    }




    // stop recording and save the data as csv
    private fun stopRecording() {
        //re check the ble services to not crash the app
        val char = measurementChar ?: return
        val gatt = bluetoothGatt ?: return

        //function from just above this to stop recording
        writeCommand("STOP")

        //stop live display of data
        waitingForFlush = true

        lastSampleTime = System.currentTimeMillis()
        setStatus("Stopped - waiting for data flush")


        //disable stop button on screen
        runOnUiThread { btnStop.isEnabled = false }


        //fancy loop basically
        Handler(Looper.getMainLooper()).post(object : Runnable {

            override fun run() {
                //if smth else set this variable off then stop everything
                if (!waitingForFlush) return

                //see how much time has passed since last sample
                val timeSinceLastSample = System.currentTimeMillis() - lastSampleTime

                //wait 1.5 seconds to see whether there are more samples
                if (timeSinceLastSample > 1500) {
                    waitingForFlush = false

                    //disable ble notifications
                    try {
                        gatt.setCharacteristicNotification(char, false)
                        val descriptor = char.getDescriptor(CCCD_UUID)
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    } catch (e: SecurityException) {
                        setStatus("Permission denied disabling notifications")
                    }

                    //save everything from the list
                    saveCSV()


                    //re enables the start recording button on the screen
                    runOnUiThread {
                        btnRecord.isEnabled = true
                        setStatus("Done! ${allSamples.size} samples saved.")
                    }

                    allSamples.clear()

                } else {
                    setStatus("Receiving data... ${allSamples.size} samples so far")
                    Handler(Looper.getMainLooper()).postDelayed(this, 500)
                }
            }
        })
    }

    // send command to reset the Squato-Meter
    private fun resetDevice() {
        writeCommand("RESET")
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                bluetoothGatt?.disconnect()
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                // ignore on cleanup
            }
            bluetoothGatt = null
            measurementChar = null
            btnRecord.isEnabled = false
            btnStop.isEnabled   = false
            btnReset.isEnabled  = false
            btnScan.isEnabled   = true
            setStatus("Device reset — tap Scan to reconnect")
        }, 500)
    }



    // save data as csv after recording stops
    private fun saveCSV() {
        try {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadsDir, "squato_data.csv")

            file.bufferedWriter().use { writer ->
                writer.write("timestamp,distance,roll,pitch,accX,accY,accZ,gyroRoll,gyroPitch,gyroYaw\n")
                for (sample in allSamples) {
                    writer.write(sample.joinToString(",") + "\n")
                }
            }

            runOnUiThread {
                Toast.makeText(
                    this,
                    "Saved ${allSamples.size} samples to Downloads/squato_data.csv",
                    Toast.LENGTH_LONG
                ).show()
            }

        } catch (e: Exception) {
            runOnUiThread { setStatus("CSV save error: ${e.message}") }
        }
    }



    //clean everything related to ble connection after disconnection
    override fun onDestroy() {
        super.onDestroy()
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (e: SecurityException) {

        }
        bluetoothGatt = null
    }

    //Ui helper
    private fun setStatus(msg: String) {
        runOnUiThread { tvStatus.text = "Status: $msg" }
    }
}