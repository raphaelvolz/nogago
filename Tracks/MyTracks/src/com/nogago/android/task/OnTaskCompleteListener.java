package com.nogago.android.task;

import android.os.AsyncTask;

public interface OnTaskCompleteListener {
	
	
    /** Notifies about task completeness */
    void onTaskComplete(AsyncTask task);
}