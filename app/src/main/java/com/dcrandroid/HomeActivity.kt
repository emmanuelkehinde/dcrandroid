/*
 * Copyright (c) 2018-2019 The Decred developers
 * Use of this source code is governed by an ISC
 * license that can be found in the LICENSE file.
 */

package com.dcrandroid

import android.animation.Animator
import android.animation.ObjectAnimator
import android.app.NotificationManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dcrandroid.activities.BaseActivity
import com.dcrandroid.adapter.NavigationTabsAdapter
import com.dcrandroid.data.Constants
import com.dcrandroid.data.Transaction
import com.dcrandroid.dialog.FullScreenBottomSheetDialog
import com.dcrandroid.dialog.ReceiveDialog
import com.dcrandroid.dialog.ResumeAccountDiscovery
import com.dcrandroid.dialog.WiFiSyncDialog
import com.dcrandroid.dialog.send.SendDialog
import com.dcrandroid.extensions.hide
import com.dcrandroid.extensions.openedWalletsList
import com.dcrandroid.extensions.show
import com.dcrandroid.fragments.MultiWalletTransactions
import com.dcrandroid.fragments.Overview
import com.dcrandroid.fragments.TransactionsFragment
import com.dcrandroid.fragments.WalletsFragment
import com.dcrandroid.fragments.more.MoreFragment
import com.dcrandroid.service.SyncService
import com.dcrandroid.util.NetworkUtil
import com.dcrandroid.util.Utils
import com.dcrandroid.util.WalletData
import com.google.gson.Gson
import dcrlibwallet.*
import kotlinx.android.synthetic.main.activity_tabs.*
import java.text.DecimalFormat
import kotlin.math.roundToInt
import kotlin.system.exitProcess

const val TAG = "HomeActivity"

class HomeActivity : BaseActivity(), SyncProgressListener, TxAndBlockNotificationListener {

    private var deviceWidth: Int = 0
    private var blockNotificationSound: Int = 0
    private lateinit var alertSound: SoundPool

    private lateinit var adapter: NavigationTabsAdapter

    private lateinit var currentFragment: Fragment
    private var currentBottomSheet: FullScreenBottomSheetDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tabs)
        setSupportActionBar(toolbar)

        if (walletData.multiWallet == null) {
            println("Restarting app")
            Utils.restartApp(this)
        }

        Utils.registerTransactionNotificationChannel(this)

        val builder = SoundPool.Builder().setMaxStreams(3)
        val attributes = AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                .setLegacyStreamType(AudioManager.STREAM_NOTIFICATION).build()
        builder.setAudioAttributes(attributes)

        alertSound = builder.build()
        blockNotificationSound = alertSound.load(this, R.raw.beep, 1)

        try {
            multiWallet?.removeSyncProgressListener(TAG)
            multiWallet?.removeTxAndBlockNotificationListener(TAG)

            multiWallet?.addSyncProgressListener(this, TAG)
            multiWallet?.addTxAndBlockNotificationListener(this, TAG)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        initNavigationTabs()

        checkWifiSync()

        fab_receive.setOnClickListener {
            currentBottomSheet = ReceiveDialog(bottomSheetDismissed)
            currentBottomSheet!!.show(this)
        }

        fab_send.setOnClickListener {
            currentBottomSheet = SendDialog(this, bottomSheetDismissed)
            currentBottomSheet!!.show(this)
        }
    }

    val bottomSheetDismissed = DialogInterface.OnDismissListener {
        currentBottomSheet = null
    }

    override fun onDestroy() {
        super.onDestroy()

        if (multiWallet == null || multiWallet?.openedWalletsCount() == 0) {
            return
        }

        val syncIntent = Intent(this, SyncService::class.java)
        stopService(syncIntent)

        multiWallet?.shutdown()
        finish()
        exitProcess(1)
    }

    private fun initNavigationTabs() {

        val displayMetrics = DisplayMetrics()
        windowManager.defaultDisplay
                .getMetrics(displayMetrics)
        deviceWidth = displayMetrics.widthPixels

        val mLayoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        recycler_view_tabs.layoutManager = mLayoutManager

        adapter = NavigationTabsAdapter(this, 0, deviceWidth, multiWallet!!.numWalletsNeedingSeedBackup()) { position ->
            switchFragment(position)
        }
        recycler_view_tabs.adapter = adapter

        switchFragment(0)

    }

    fun refreshNavigationTabs() {
        adapter.backupsNeeded = multiWallet!!.numWalletsNeedingSeedBackup()
        adapter.notifyItemChanged(2) // Wallets Page
    }

    private fun setTabIndicator() {
        tab_indicator.post {
            val tabWidth = deviceWidth / 4
            val tabIndicatorWidth = resources.getDimension(R.dimen.tab_indicator_width)

            var leftMargin = tabWidth * adapter.activeTab
            leftMargin += ((tabWidth - tabIndicatorWidth) / 2f).roundToInt()

            ObjectAnimator.ofFloat(tab_indicator, View.TRANSLATION_X, leftMargin.toFloat()).apply {
                duration = 350
                start()
            }
        }
    }

    private fun showOrHideFab(position: Int) {
        send_receive_layout.post {
            if (position < 2) { // show send and receive buttons for overview & transactions page
                send_receive_layout.show()
                ObjectAnimator.ofFloat(send_receive_layout, View.TRANSLATION_Y, 0f).setDuration(350).start() // bring view down
            } else {
                val objectAnimator = ObjectAnimator.ofFloat(send_receive_layout, View.TRANSLATION_Y, send_receive_layout.height.toFloat())

                objectAnimator.addListener(object : Animator.AnimatorListener {
                    override fun onAnimationRepeat(animation: Animator?) {
                    }

                    override fun onAnimationEnd(animation: Animator?) {
                        send_receive_layout.hide()
                    }

                    override fun onAnimationCancel(animation: Animator?) {
                    }

                    override fun onAnimationStart(animation: Animator?) {
                    }
                })

                objectAnimator.duration = 350

                objectAnimator.start()
            }
        }
    }

    fun switchFragment(position: Int) {

        currentFragment = when (position) {
            0 -> Overview()
            1 -> {
                if (multiWallet!!.openedWalletsCount() > 1) {
                    MultiWalletTransactions()
                } else {
                    val wallet = multiWallet!!.openedWalletsList()[0]
                    TransactionsFragment().setWalletID(wallet.id)
                }
            }
            2 -> WalletsFragment()
            else -> MoreFragment()
        }

        supportFragmentManager
                .beginTransaction()
                .replace(R.id.frame, currentFragment)
                .commit()

        setTabIndicator()

        showOrHideFab(position)

        adapter.changeActiveTab(position)
    }

    fun setToolbarTitle(title: CharSequence, showShadow: Boolean) {
        toolbar_title.text = title
        app_bar.elevation = if (showShadow) {
            resources.getDimension(R.dimen.app_bar_elevation)
        } else {
            0f
        }
    }

    fun checkWifiSync() {
        if (!multiWallet!!.readBoolConfigValueForKey(Dcrlibwallet.SyncOnCellularConfigKey, Constants.DEF_SYNC_ON_CELLULAR)) {
            // Check if wifi is connected
            val isWifiConnected = this.let { NetworkUtil.isWifiConnected(it) }
            if (!isWifiConnected) {
                showWifiNotice()
                return
            }
        }

        startSyncing()
    }

    private fun showWifiNotice() {
        val wifiSyncDialog = WiFiSyncDialog(this)
                .setPositiveButton(DialogInterface.OnClickListener { dialog, _ ->
                    startSyncing()

                    val syncDialog = dialog as WiFiSyncDialog
                    multiWallet!!.setBoolConfigValueForKey(Dcrlibwallet.SyncOnCellularConfigKey, syncDialog.checked)

                })

        wifiSyncDialog.setOnCancelListener {
            sendBroadcast(Intent(Constants.SYNCED))
        }

        wifiSyncDialog.show()
    }

    fun startSyncing() {
        for (w in multiWallet!!.openedWalletsList()) {
            if (!w.hasDiscoveredAccounts && w.isLocked) {
                ResumeAccountDiscovery()
                        .setWalletID(w.id)
                        .show(supportFragmentManager, ResumeAccountDiscovery::javaClass.name)
                return
            }
        }
        sendBroadcast(Intent(Constants.SYNCED))
        val syncIntent = Intent(this, SyncService::class.java)
        startService(syncIntent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        currentBottomSheet?.onActivityResult(requestCode, resultCode, data)
    }

    // -- Block Notification

    override fun onTransactionConfirmed(walletID: Long, hash: String, blockHeight: Int) {
    }

    override fun onBlockAttached(walletID: Long, blockHeight: Int) {
        val beepNewBlocks = multiWallet!!.readBoolConfigValueForKey(Dcrlibwallet.BeepNewBlocksConfigKey, false)
        if (beepNewBlocks && !multiWallet!!.isSyncing) {
            alertSound.play(blockNotificationSound, 1f, 1f, 1, 0, 1f)
        }
    }

    override fun onTransaction(transactionJson: String?) {
        val gson = Gson()
        val transaction = gson.fromJson(transactionJson, Transaction::class.java)

        if (transaction.direction == Dcrlibwallet.TxDirectionReceived) {
            val dcrFormat = DecimalFormat("#.######## DCR")

            val amount = Dcrlibwallet.amountCoin(transaction.amount)
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            Utils.sendTransactionNotification(this, notificationManager, dcrFormat.format(amount),
                    transaction.timestampMillis.toInt(), transaction.walletID)
        }
    }

    // -- Sync Progress Listener

    override fun onSyncStarted() {
    }

    override fun onHeadersRescanProgress(headersRescanProgress: HeadersRescanProgressReport?) {
    }

    override fun onAddressDiscoveryProgress(addressDiscoveryProgress: AddressDiscoveryProgressReport?) {
    }

    override fun onSyncCanceled(willRestart: Boolean) {
    }

    override fun onPeerConnectedOrDisconnected(numberOfConnectedPeers: Int) {
        WalletData.instance.peers = numberOfConnectedPeers
    }

    override fun onSyncCompleted() {
    }

    override fun onHeadersFetchProgress(headersFetchProgress: HeadersFetchProgressReport?) {
    }

    override fun onSyncEndedWithError(err: java.lang.Exception?) {
    }

    override fun debug(debugInfo: DebugInfo?) {
    }
}