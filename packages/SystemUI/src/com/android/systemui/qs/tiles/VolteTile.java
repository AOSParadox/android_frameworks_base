/*
 * Copyright (c) 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.tiles;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.VoLteServiceState;
import android.util.Log;
import android.widget.Switch;

import com.android.ims.ImsManager;
import com.android.ims.ImsException;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto.MetricsEvent;
import com.android.systemui.R;
import com.android.systemui.qs.QSTile;
import com.android.systemui.qs.GlobalSetting;

public class VolteTile extends QSTile<QSTile.BooleanState> {

    private final GlobalSetting mSetting;
    private boolean mListening;
    private PhoneStateListener mPhoneStateListener = null;

    public VolteTile(Host host) {
        super(host);

        mSetting = new GlobalSetting(mContext, mHandler, Global.ENHANCED_4G_MODE_ENABLED) {
            @Override
            protected void handleValueChanged(int value) {
                handleRefreshState(value);
            }
        };
    }

    @Override
    public boolean isAvailable() {
        return isVolteAvailable();
    }

    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    }

    @Override
    protected void handleClick() {
        // If we're in a call, do nothing.
        if (getTelephonyManager().getCallState() != TelephonyManager.CALL_STATE_IDLE) {
            return;
        }
        final boolean activated = !mState.value;
        MetricsLogger.action(mContext, getMetricsCategory(), activated);
        setEnabled(!mState.value && isVolteAvailable());
    }

    private void setEnabled(boolean enabled) {
        if (setVolte(enabled)) {
            setGlobal(enabled);
        }
    }

    @Override
    protected void handleUserSwitch(int newUserId) {
        super.handleUserSwitch(newUserId);
    }

    /**
     * Write VoLTE state to global settings.
     */
    private void setGlobal(boolean enabled) {
        int value = enabled ? 1 : 0;
        Global.putInt(mContext.getContentResolver(), Global.ENHANCED_4G_MODE_ENABLED, value);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        final boolean isActivated = isVolteInUse();
        state.value = isVolteInUse();
        state.label = mContext.getString(R.string.quick_settings_volte_label);
        state.icon = ResourceIcon.get(isActivated ? R.drawable.ic_volte_enable
                : R.drawable.ic_volte_disable);
        state.contentDescription = mContext.getString(isActivated
                ? R.string.quick_settings_volte_summary_enabled
                : R.string.quick_settings_volte_summary_disabled);
        state.minimalAccessibilityClassName = state.expandedAccessibilityClassName
                = Switch.class.getName();
    }

    @Override
    public int getMetricsCategory() {
        return MetricsEvent.QS_VOLTE;
    }

    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DATA_ROAMING_SETTINGS);
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        trackVolteState(listening);
        mSetting.setListening(listening);
    }

    private TelephonyManager getTelephonyManager() {
        return (TelephonyManager) mContext.getSystemService(Context.TELEPHONY_SERVICE);
    }

    private TelecomManager getTelecomManager() {
        return (TelecomManager) mContext.getSystemService(Context.TELECOM_SERVICE);
    }

    @Override
    public CharSequence getTileLabel() {
        return mContext.getString(R.string.quick_settings_volte_label);
    }

    public void onActivated(boolean activated) {
        refreshState();
    }

    //==============================================================

    /*
     * Programming note: everything below this point encapsulates the
     * os-dependent VoLTE interface. The IMS and VoLTE classes used
     * here are not part of the published API and could change in the
     * future. If this interface changes, the changes should all be here.
     */

    private boolean isVolteAvailable() {
        return ImsManager.isVolteEnabledByPlatform(mContext) &&
               ImsManager.isVolteProvisionedOnDevice(mContext);
    }

    private boolean isVolteInUse() {
        return isVolteAvailable() &&
               ImsManager.isEnhanced4gLteModeSettingEnabledByUser(mContext);
    }

    /**
     * Write VoLTE state to IMS manager.
     */
    private boolean setVolte(boolean enabled) {
        ImsManager imsManager = ImsManager.getInstance(mContext,
            SubscriptionManager.getDefaultVoicePhoneId());
        if (imsManager != null) {
            try {
                imsManager.setAdvanced4GMode(enabled);
		return true;
            } catch (ImsException ie) {
                return false;
            }
        } else {
	    return false;
	}
    }

    /**
     * Set up or remove a listener for changes in VoLTE. Client should call
     * this any time the call method changes.
     */
    private void trackVolteState(boolean listening) {
        int slotId = getDefaultSimSlot();
        TelephonyManager mgr = getTelephonyManager();
        if (mPhoneStateListener != null) {
            mgr.listen(mPhoneStateListener, PhoneStateListener.LISTEN_NONE);
            mPhoneStateListener = null;
        }
        if (!listening || !isVolteAvailable()) return;
        SubscriptionManager smgr = SubscriptionManager.from(mContext);
        android.telephony.SubscriptionInfo info =
            smgr.getActiveSubscriptionInfoForSimSlotIndex(slotId);
        if (info == null) {
            return;
        }
        mPhoneStateListener = new MobilePhoneStateListener(info.getSubscriptionId());
        int eventMask = PhoneStateListener.LISTEN_VOLTE_STATE;
        mgr.listen(mPhoneStateListener, eventMask);
    }

    private class MobilePhoneStateListener extends PhoneStateListener {
        public MobilePhoneStateListener(int subId) {
            super(subId);
        }
        @Override
        public void onVoLteServiceStateChanged(VoLteServiceState stateInfo) {
            refreshState();
        }
    }

    /**
     * Return the sim slot ID that corresponds to the default sim for
     * outgoing calls. This could change, so don't cache the output of this
     * function.
     */
    private int getDefaultSimSlot() {
        TelecomManager mgr = getTelecomManager();
        PhoneAccountHandle handle =
            mgr.getDefaultOutgoingPhoneAccount(PhoneAccount.SCHEME_TEL);
        return SubscriptionManager.getSlotId(Integer.parseInt(handle.getId()));
    }
}
