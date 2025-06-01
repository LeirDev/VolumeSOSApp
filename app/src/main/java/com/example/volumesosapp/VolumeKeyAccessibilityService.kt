package com.example.volumesosapp // Verifique seu package name

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

class VolumeKeyAccessibilityService : AccessibilityService() {

    private val TAG = "VolumeAccessibility"

    // Variáveis para controle do botão de volume (semelhante à MainActivity)
    private var volumeUpKeyPressCount = 0
    private var lastVolumeUpKeyPressTime: Long = 0
    private val MAX_PRESS_INTERVAL_MS = 1500L
    private val REQUIRED_PRESSES = 3

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Este método é chamado para eventos de UI (não o usaremos para teclas)
        // Log.d(TAG, "onAccessibilityEvent: ${event?.eventType}")
    }

    override fun onInterrupt() {
        // Chamado quando o sistema quer interromper o feedback do serviço
        Log.d(TAG, "onInterrupt")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "Serviço de Acessibilidade Conectado")
        // Configura o serviço para receber eventos de tecla
        val info = AccessibilityServiceInfo().apply {
            // Tipos de eventos que queremos ouvir (não precisamos de eventos de UI)
            eventTypes = 0 // Nenhum evento de UI
            // Flags importantes:
            // flagRequestFilterKeyEvents: Pede para receber eventos de tecla
            // flagReportViewIds: (Opcional) Reporta IDs de views
            // flagIncludeNotImportantViews: (Opcional) Inclui views não importantes
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            // Feedback (não precisamos de feedback falado, etc.)
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            // Timeout de notificação (não relevante aqui)
            notificationTimeout = 100
        }
        this.serviceInfo = info
        Log.d(TAG, "Serviço configurado para filtrar eventos de tecla.")
    }

    // *** Este é o método chave para interceptar teclas ***
    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.onKeyEvent(event)

        // Processa apenas eventos de PRESSIONAR (ACTION_DOWN)
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                Log.d(TAG, "Volume UP pressionado (detectado pelo AccessibilityService)")
                val currentTime = System.currentTimeMillis()

                if (lastVolumeUpKeyPressTime == 0L || (currentTime - lastVolumeUpKeyPressTime) > MAX_PRESS_INTERVAL_MS) {
                    volumeUpKeyPressCount = 1
                    Log.d(TAG, "Nova sequência. Contagem: $volumeUpKeyPressCount")
                } else {
                    volumeUpKeyPressCount++
                    Log.d(TAG, "Contagem incrementada: $volumeUpKeyPressCount")
                }
                lastVolumeUpKeyPressTime = currentTime

                if (volumeUpKeyPressCount == REQUIRED_PRESSES) {
                    Log.d(TAG, "$REQUIRED_PRESSES pressões detectadas! Acionando SOS...")
                    // !!! AQUI CHAMAREMOS A LÓGICA SOS !!!
                    triggerSosActionFromAccessibilityService()
                    volumeUpKeyPressCount = 0
                    lastVolumeUpKeyPressTime = 0L
                }
                // Retorna true para consumir o evento (evitar que o volume mude)
                // ATENÇÃO: Isso pode impedir o controle de volume normal!
                return true
            } else {
                // Reseta a contagem se outra tecla for pressionada
                if (volumeUpKeyPressCount > 0) {
                    Log.d(TAG, "Outra tecla. Resetando contagem.")
                    volumeUpKeyPressCount = 0
                    lastVolumeUpKeyPressTime = 0L
                }
            }
        }
        // Se não for Volume Up ou não consumimos, deixa o sistema tratar
        return super.onKeyEvent(event)
    }

    private fun triggerSosActionFromAccessibilityService() {
        Log.d(TAG, "Tentando acionar a ação SOS a partir do Serviço de Acessibilidade...")
        // A forma mais simples é iniciar o nosso outro serviço (VolumeMonitoringService)
        // passando uma ação específica para ele lidar com o SOS.
        val intent = Intent(this, VolumeMonitoringService::class.java)
        intent.action = "ACTION_TRIGGER_SOS" // Usa a mesma ação que a notificação
        try {
            startService(intent)
            Log.d(TAG, "Comando enviado para VolumeMonitoringService iniciar o SOS.")
        } catch (e: Exception) {
            // Pode dar erro se o serviço não puder ser iniciado em background (restrições do Android)
            Log.e(TAG, "Erro ao tentar iniciar VolumeMonitoringService: ", e)
            // TODO: Implementar um fallback? Mostrar um Toast?
        }
    }
}
