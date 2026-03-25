package com.example.registrabuche

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.example.registrabuche.util.Event

class MainViewModel : ViewModel() {

    private val _saveLocationEvent = MutableLiveData<Event<Unit>>()
    val saveLocationEvent: LiveData<Event<Unit>> = _saveLocationEvent

    fun onRegisterButtonPressed() {
        _saveLocationEvent.value = Event(Unit)
    }

    fun onHardwareKeyPressed() {
        _saveLocationEvent.value = Event(Unit)
    }
}
