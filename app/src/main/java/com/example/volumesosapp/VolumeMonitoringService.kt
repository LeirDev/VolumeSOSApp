package com.example.volumesosapp // Certifique-se que é o seu package name!

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import android.app.* // Para Notification, NotificationChannel, PendingIntent
import android.content.Context
import android.graphics.Color // Para a cor do canal (opcional)
import android.os.Build // Para checar a versão do Android
import androidx.core.app.NotificationCompat // Para construir a notificação
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import java.util.concurrent.atomic.AtomicBoolean // Para controle de trigger
import kotlinx.coroutines.*

class VolumeMonitoringService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var cancellationTokenSource = CancellationTokenSource()
    private val isSendingSms = AtomicBoolean(false)

    private val TAG = "VolumeSOS_Service"
    private val NOTIFICATION_CHANNEL_ID = "com.example.volumesosapp.channel" // ID único do canal
    private val NOTIFICATION_ID = 1 // ID único da notificação
    private val locationTimeoutMs = 7000L // 7 segundos de timeout para localização

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelName = "Volume SOS Service Channel"
            val channelDescription = "Notificação persistente para o serviço SOS"
            val importance = NotificationManager.IMPORTANCE_LOW // Baixa importância (não faz som/vibração)
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, channelName, importance).apply {
                description = channelDescription
                // Configurações opcionais do canal:
                // enableLights(true)
                // lightColor = Color.BLUE
                // enableVibration(false)
            }
            // Registra o canal com o sistema
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            Log.d(TAG, "Canal de notificação criado.")
        }
    }

    // Função para criar a notificação em si
    private fun createNotification(contentText: String): Notification {
        // Intent para abrir a MainActivity quando a notificação for clicada
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE // Flag necessária
        )

        // --- Ação do Botão SOS na Notificação (será implementada a lógica depois) ---
        val sosActionIntent = Intent(this, VolumeMonitoringService::class.java).apply {
            action = "ACTION_TRIGGER_SOS" // Uma string para identificar a ação
        }
        val sosPendingIntent = PendingIntent.getService(
            this, 1, sosActionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // --- Fim da Ação do Botão ---

        // Constrói a notificação
        val notificationBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Volume SOS Ativo") // Título da notificação
            .setContentText(contentText) // Texto principal
            .setSmallIcon(android.R.drawable.ic_dialog_alert) // Ícone (!!! SUBSTITUA POR UM ÍCONE REAL DO SEU APP !!!)
            .setContentIntent(pendingIntent) // Ação ao clicar na notificação
            .setOngoing(true) // Torna a notificação não dispensável (obrigatório para foreground)
            .addAction(android.R.drawable.ic_menu_send, "Enviar SOS Agora", sosPendingIntent) // Adiciona o botão de ação
            .setPriority(NotificationCompat.PRIORITY_LOW) // Prioridade baixa
            .setCategory(NotificationCompat.CATEGORY_SERVICE) // Categoria

        Log.d(TAG, "Notificação criada com texto: $contentText")
        return notificationBuilder.build()
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Serviço onCreate")
        createNotificationChannel() // Cria o canal ao iniciar o serviço
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)// Inicializações futuras (FusedLocationClient) virão aqui
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Serviço onStartCommand")

        val notificationText = "Serviço SOS rodando. Use o botão de volume no app ou esta notificação."
        val notification = createNotification(notificationText)

        // Inicia o serviço em modo Foreground
        startForeground(NOTIFICATION_ID, notification)
        Log.d(TAG, "Serviço iniciado em modo foreground.")

        // --- Lógica para tratar a ação do botão SOS (ACTION_TRIGGER_SOS) virá aqui ---
        if (intent?.action == "ACTION_TRIGGER_SOS") {
            Log.d(TAG, "Ação ACTION_TRIGGER_SOS recebida no serviço!")
            triggerSosActionFromService() // Chama a função SOS do serviço
        }
        // --- Fim da lógica da ação ---

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Serviço onDestroy")
        // Remove a notificação ao parar o serviço
        cancellationTokenSource.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        Log.d(TAG, "Serviço parado do modo foreground.")
        // Limpeza de recursos futuros (cancelar localização) virá aqui
    }


    override fun onBind(intent: Intent?): IBinder? {
        // Não permitiremos que outros apps se conectem a este serviço,
        // então retornamos null.
        return null
    }

    @SuppressLint("MissingPermission")
    private fun obterLocalizacaoAtualComTimeout(callback: (Location?) -> Unit) {
        val hasFineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocationPermission && !hasCoarseLocationPermission) {
            Log.w(TAG, "Permissão de localização não concedida ao tentar obter localização.")
            callback(null)
            return
        }

        var callbackCalled = AtomicBoolean(false)
        val mainScope = CoroutineScope(Dispatchers.Main)

        Log.d(TAG, "Serviço tentando obter localização atual (lastLocation)...")
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (!callbackCalled.getAndSet(true)) {
                    if (location != null) {
                        Log.d(TAG, "Serviço obteve localização: Lat=${location.latitude}, Lon=${location.longitude}")
                        callback(location)
                    } else {
                        Log.w(TAG, "Serviço falhou ao obter localização (lastLocation nulo). Tentando getCurrentLocation...")
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            CancellationTokenSource().token
                        ).addOnSuccessListener { loc: Location? ->
                            if (!callbackCalled.getAndSet(true)) {
                                if (loc != null) {
                                    Log.d(TAG, "Serviço obteve localização via getCurrentLocation: Lat=${loc.latitude}, Lon=${loc.longitude}")
                                    callback(loc)
                                } else {
                                    Log.w(TAG, "Serviço falhou ao obter localização (getCurrentLocation também nulo).")
                                    callback(null)
                                }
                            }
                        }.addOnFailureListener { exception ->
                            if (!callbackCalled.getAndSet(true)) {
                                Log.e(TAG, "Serviço: Erro ao obter localização (getCurrentLocation)", exception)
                                callback(null)
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { exception ->
                if (!callbackCalled.getAndSet(true)) {
                    Log.e(TAG, "Serviço: Erro ao obter localização (lastLocation)", exception)
                    callback(null)
                }
            }

        // Timeout manual para garantir que o callback sempre será chamado
        mainScope.launch {
            delay(locationTimeoutMs)
            if (!callbackCalled.getAndSet(true)) {
                Log.w(TAG, "Timeout ao tentar obter localização no serviço. Seguindo sem localização.")
                callback(null)
            }
        }
    }

    // Modificada para aceitar um callback de conclusão
    private fun enviarSmsComLocalizacao(context: Context, location: Location?, onComplete: (Boolean) -> Unit) {
        val numeroDestino = "11934173173" // !!! SUBSTITUA PELO NÚMERO REAL !!!
        val mensagemFinal = if (location != null) {
            val latitude = location.latitude
            val longitude = location.longitude
            val googleMapsLink = "https://maps.google.com/?q=$latitude,$longitude"
            "SOS! Preciso de ajuda.\nMinha localização:\n$googleMapsLink"
        } else {
            "SOS! Preciso de ajuda.\nLocalização não disponível."
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(mensagemFinal)
                smsManager.sendMultipartTextMessage(numeroDestino, null, parts, null, null)
                Log.d(TAG, "---> SMS multipart enviado para $numeroDestino")
                onComplete(true)
            } catch (e: Exception) {
                Log.e(TAG, "---> ERRO ao tentar enviar SMS", e)
                onComplete(false)
            }
        } else {
            Log.w(TAG, "---> Tentativa de enviar SMS SEM PERMISSÃO.")
            onComplete(false)
        }
    }


    // Função centralizada no serviço para acionar o SOS
    private fun triggerSosActionFromService() {
        if (isSendingSms.compareAndSet(false, true)) {
            Log.d(TAG, "Serviço: Ação SOS acionada! Tentando obter localização...")
            updateNotification("Enviando SOS...")

            obterLocalizacaoAtualComTimeout { location ->
                Log.d(TAG, "Callback de localização recebido no serviço. Location: $location")
                enviarSmsComLocalizacao(this, location) { success ->
                    isSendingSms.set(false)
                    updateNotification(if (success) "SOS enviado com sucesso!" else "Falha ao enviar SOS.")
                    Toast.makeText(this, if (success) "SMS de SOS enviado!" else "Falha ao enviar SMS de SOS.", Toast.LENGTH_LONG).show()
                    Log.d(TAG, "Resultado do envio de SMS: $success")
                }
            }
        } else {
            Log.d(TAG, "Serviço: SOS já em andamento, ignorando novo trigger.")
            Toast.makeText(this, "Envio de SOS já está em progresso.", Toast.LENGTH_SHORT).show()
        }
    }

    // Função para atualizar o texto da notificação (precisa ser criada ou adaptada)
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText) // Recria com o novo texto
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
        Log.d(TAG, "Notificação atualizada: $contentText")
    }
// --- Fim da Lógica de Localização e SMS no Serviço ---

}
