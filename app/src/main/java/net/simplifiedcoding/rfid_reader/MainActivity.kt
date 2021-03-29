package net.simplifiedcoding.rfid_reader

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.uk.tsl.rfid.DeviceListActivity
import com.uk.tsl.rfid.DeviceListActivity.EXTRA_DEVICE_ACTION
import com.uk.tsl.rfid.DeviceListActivity.EXTRA_DEVICE_INDEX
import com.uk.tsl.rfid.asciiprotocol.AsciiCommander
import com.uk.tsl.rfid.asciiprotocol.commands.BatteryStatusCommand
import com.uk.tsl.rfid.asciiprotocol.device.*
import com.uk.tsl.rfid.asciiprotocol.responders.LoggerResponder
import com.uk.tsl.utils.Observable


open class MainActivity : AppCompatActivity() {

    private var mConnectMenuItem: MenuItem? = null
    private var mDisconnectMenuItem: MenuItem? = null
    private var mReader: Reader? = null
    private var mIsSelectingReader = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        AsciiCommander.createSharedInstance(applicationContext)
        val commander = AsciiCommander.sharedInstance()
        commander.clearResponders()
        commander.addResponder(LoggerResponder())
        commander.addSynchronousResponder()
        ReaderManager.create(applicationContext)

        ReaderManager.sharedInstance().readerList.readerAddedEvent().addObserver(mAddedObserver);
        ReaderManager.sharedInstance().readerList.readerUpdatedEvent().addObserver(mUpdatedObserver);
        ReaderManager.sharedInstance().readerList.readerRemovedEvent().addObserver(mRemovedObserver);
    }

    override fun onDestroy() {
        super.onDestroy()
        ReaderManager.sharedInstance().readerList.readerAddedEvent().removeObserver(mAddedObserver)
        ReaderManager.sharedInstance().readerList.readerUpdatedEvent().removeObserver(mUpdatedObserver)
        ReaderManager.sharedInstance().readerList.readerRemovedEvent().removeObserver(mRemovedObserver)
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(mMessageReceiver, IntentFilter(AsciiCommander.STATE_CHANGED_NOTIFICATION))
        val readerManagerDidCauseOnPause = ReaderManager.sharedInstance().didCauseOnPause()
        ReaderManager.sharedInstance().updateList()
        mIsSelectingReader = false
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mMessageReceiver)
        if (!mIsSelectingReader && !ReaderManager.sharedInstance().didCauseOnPause() && mReader != null) {
            mReader?.disconnect()
        }
        ReaderManager.sharedInstance().onPause()
    }

    fun autoSelectReader(attemptReconnect: Boolean) {
        val readerList = ReaderManager.sharedInstance().readerList
        var usbReader: Reader? = null

        if (readerList.list().size >= 1) {
            readerList.list().forEach {
                val transport = mReader?.activeTransport
                if (mReader?.hasTransportOfType(TransportType.USB) == true) {
                    usbReader = mReader
                    return@forEach
                }
            }
        }

        if (mReader == null) {
            if (usbReader != null) {
                mReader = usbReader;
                AsciiCommander.sharedInstance().reader = mReader;
            }
        } else {
            val activeTransport = mReader?.activeTransport
            if (activeTransport != null && activeTransport.type() != TransportType.USB && usbReader != null) {
                appendMessage("Disconnecting from: " + mReader?.displayName);
                mReader?.disconnect();
                mReader = usbReader;
                AsciiCommander.sharedInstance().reader = mReader
            }

            if (mReader != null && mReader?.isConnecting != true && (mReader?.activeTransport == null || mReader?.activeTransport?.connectionStatus()?.value() == ConnectionState.DISCONNECTED)) {
                if (attemptReconnect) {
                    if (mReader?.allowMultipleTransports() == true || mReader?.lastTransportType == null) {
                        if (mReader?.connect() == true) {
                            appendMessage("Connecting to: " + mReader?.displayName)
                        } else {
                            if (mReader?.connect(mReader?.lastTransportType) == true) {
                                appendMessage("Connecting (over last transport) to: " + mReader?.displayName)
                            }
                        }
                    }
                }
            }
        }
    }

    private val mAddedObserver = Observable.Observer<Reader> { observable, reader ->
        autoSelectReader(true)
    }

    private val mUpdatedObserver = Observable.Observer<Reader> { observable, reader ->
        autoSelectReader(true)
    }

    private val mRemovedObserver = Observable.Observer<Reader> { observable, reader ->
        if (reader == mReader) {
            mReader = null;
            AsciiCommander.sharedInstance().reader = mReader;
        }
    }

    private val mMessageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val connectionStateMsg = AsciiCommander.sharedInstance().connectionState.toString()
            appendMessage("Ascii commander state changed ${AsciiCommander.sharedInstance().connectionState} $connectionStateMsg")
            if (AsciiCommander.sharedInstance() != null) {
                if (AsciiCommander.sharedInstance().isConnected) {
                    val bCommand = BatteryStatusCommand.synchronousCommand()
                    AsciiCommander.sharedInstance().executeCommand(bCommand)
                    val batteryLevel = bCommand.batteryLevel
                } else if (AsciiCommander.sharedInstance().connectionState == ConnectionState.DISCONNECTED) {
                    if (mReader != null) {
                        if (mReader?.wasLastConnectSuccessful() != true) {
                            mReader = null
                        }
                    }
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu, menu)
        mConnectMenuItem = menu?.findItem(R.id.connect_reader_menu_item)
        mDisconnectMenuItem = menu?.findItem(R.id.disconnect_reader_menu_item)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val isConnected: Boolean = AsciiCommander.sharedInstance().isConnected
        mDisconnectMenuItem?.isEnabled = isConnected
        mConnectMenuItem!!.isEnabled = true
        mConnectMenuItem!!.setTitle(if (mReader != null && mReader!!.isConnected) R.string.change_reader_menu_item_text else R.string.connect_reader_menu_item_text)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.connect_reader_menu_item -> {
                var index = -1
                if (mReader != null) {
                    index = ReaderManager.sharedInstance().readerList.list().indexOf(mReader)
                }
                val selectIntent = Intent(this, DeviceListActivity::class.java)
                if (index > 0) {
                    selectIntent.putExtra(EXTRA_DEVICE_INDEX, index)
                    startActivityForResult(selectIntent, DeviceListActivity.SELECT_DEVICE_REQUEST)
                }
            }
            R.id.disconnect_reader_menu_item -> {
                if (mReader != null) {
                    mReader?.disconnect();
                    mReader = null;
                }
            }
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            DeviceListActivity.SELECT_DEVICE_REQUEST -> {
                if (resultCode == Activity.RESULT_OK) {
                    val readerIndex = data?.extras?.getInt(EXTRA_DEVICE_INDEX)
                    val chosenReader = ReaderManager.sharedInstance().readerList.list()[readerIndex
                            ?: 0]
                    val action = data?.extras?.getInt(EXTRA_DEVICE_ACTION)
                    if (action == DeviceListActivity.DEVICE_CHANGE || action == DeviceListActivity.DEVICE_DISCONNECT) {
                        mReader?.disconnect()
                        if (action == DeviceListActivity.DEVICE_DISCONNECT) {
                            mReader = null
                        }
                    }
                    if (action == DeviceListActivity.DEVICE_CHANGE || action == DeviceListActivity.DEVICE_CONNECT) {
                        mReader = chosenReader
                        AsciiCommander.sharedInstance().reader = mReader
                    }
                }
            }
        }
    }

    private fun appendMessage(message: String) {
        findViewById<TextView>(R.id.textView).also {
            var text = it.text.toString().trim()
            text += "\n"
            text += message
            it.text = text
        }
    }
}
