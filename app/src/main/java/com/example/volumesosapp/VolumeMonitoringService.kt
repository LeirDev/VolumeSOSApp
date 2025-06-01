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



class VolumeMonitoringService : Service() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var cancellationTokenSource = CancellationTokenSource()
    private val isSendingSms = AtomicBoolean(false)

    private val TAG = "VolumeSOS_Service"
    private val NOTIFICATION_CHANNEL_ID = "com.example.volumesosapp.channel" // ID único do canal
    private val NOTIFICATION_ID = 1 // ID único da notificação

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
            // Aqui chamaremos a função de enviar SMS no próximo passo
            Toast.makeText(this, "SOS via notificação (ainda não implementado)...", Toast.LENGTH_SHORT).show()
        }
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
    private fun obterLocalizacaoAtual(callback: (Location?) -> Unit) {
        // Verifica permissões (usando 'this' como Context)
        val hasFineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocationPermission && !hasCoarseLocationPermission) {
            Log.w(TAG, "Permissão de localização não concedida ao tentar obter localização.")
            // Não mostramos Toast do serviço, apenas logamos e retornamos null
            callback(null)
            return
        }

        Log.d(TAG, "Serviço tentando obter localização atual...")
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellationTokenSource.token
        ).addOnSuccessListener { location: Location? ->
            if (location != null) {
                Log.d(TAG, "Serviço obteve localização: Lat=${location.latitude}, Lon=${location.longitude}")
                callback(location)
            } else {
                Log.w(TAG, "Serviço falhou ao obter localização (resultado nulo).")
                callback(null)
            }
        }.addOnFailureListener { exception ->
            Log.e(TAG, "Serviço: Erro ao obter localização", exception)
            callback(null)
        }
    }

    // Modificada para aceitar um callback de conclusão
    private fun enviarSmsComLocalizacao(context: Context, location: Location?, onComplete: (Boolean) -> Unit) {
        val numeroDestino = "11934173173" // !!! SUBSTITUA PELO NÚMERO REAL !!!
        val mensagemBase = "SOS! Preciso de ajuda."
        val mensagemFinal: String

        if (location != null) {
            val latitude = location.latitude
            val longitude = location.longitude
            // Envia as coordenadas diretamente
            mensagemFinal = "$mensagemBase Minha localização: Lat $latitude, Lon $longitude"
            Log.d(TAG, "Preparando SMS com coordenadas: $mensagemFinal")
        } else {
            mensagemFinal = "$mensagemBase (Localização não disponível )"
            Log.d(TAG, "Preparando SMS sem coordenadas: $mensagemFinal")
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) {
            try {
                Log.d(TAG, "---> TENTANDO ENVIAR SMS para $numeroDestino com mensagem: '$mensagemFinal'") // Log Adicional
                val smsManager = context.getSystemService(SmsManager::class.java)
                smsManager.sendTextMessage(numeroDestino, null, mensagemFinal, null, null)
                Log.d(TAG, "---> SMS enviado (sem erro na chamada) para $numeroDestino") // Log Adicional
                onComplete(true) // Sucesso (aparente)
            } catch (e: Exception) {
                Log.e(TAG, "---> ERRO ao tentar enviar SMS", e) // Log Adicional
                onComplete(false) // Falha
            }
        } else {
            Log.w(TAG, "---> Tentativa de enviar SMS SEM PERMISSÃO.") // Log Adicional
            onComplete(false) // Falha (sem permissão)
        }
    }


    // Função centralizada no serviço para acionar o SOS
    private fun triggerSosActionFromService() {
        // Verifica se já não está enviando para evitar múltiplos cliques
        if (isSendingSms.compareAndSet(false, true)) {
            Log.d(TAG, "Serviço: Ação SOS acionada! Tentando obter localização...")
            // Atualiza notificação para indicar que está enviando
            updateNotification("Enviando SOS...")

            obterLocalizacaoAtual { location ->
                enviarSmsComLocalizacao(this, location) { success ->
                    // Quando o envio terminar (sucesso ou falha):
                    isSendingSms.set(false) // Libera o flag
                    // Atualiza a notificação com o resultado
                    updateNotification(if (success) "SOS enviado com sucesso!" else "Falha ao enviar SOS.")
                    // Poderia agendar para voltar ao texto normal depois de um tempo
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
