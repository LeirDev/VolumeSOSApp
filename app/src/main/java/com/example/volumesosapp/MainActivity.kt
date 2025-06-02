package com.example.volumesosapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.volumesosapp.ui.theme.VolumeSOSAppTheme
import androidx.compose.material3.Button
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.annotation.SuppressLint
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import android.util.Log // Para mensagens de depuração
import android.content.Context
import android.telephony.SmsManager
import android.view.KeyEvent
import android.content.Intent
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var cancellationTokenSource = CancellationTokenSource()
    private val TAG = "MainActivitySOS" // Para logs
    // ... (variáveis fusedLocationClient, cancellationTokenSource, TAG continuam aqui)

    // --- Variáveis para controle do botão de volume ---
    private var volumeUpKeyPressCount = 0
    private var lastVolumeUpKeyPressTime: Long = 0
    private val MAX_PRESS_INTERVAL_MS = 1500L // Intervalo máximo de 1.5 segundos entre pressionamentos
    private val REQUIRED_PRESSES = 3          // Número de pressionamentos necessários
    // --- Fim das variáveis de volume ---

    // ... (o código das permissões continua aqui) ...

    // --- Lógica de Permissões ---
    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        // ... (verificação das permissões como antes) ...

        val smsGranted = permissions[Manifest.permission.SEND_SMS] ?: false
        // ... (outras verificações)

        // Inicia o serviço SE a permissão de SMS foi concedida
        if (smsGranted) {
            Log.d(TAG, "Permissão de SMS concedida. Iniciando o serviço.")
            startVolumeMonitoringService()
        } else {
            Log.w(TAG, "Permissão de SMS negada. Serviço não iniciado.")
            Toast.makeText(this, "Serviço SOS não pode iniciar sem permissão de SMS.", Toast.LENGTH_LONG).show()
        }
    }


    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        // Verifica SMS
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.SEND_SMS)
        }
        // Verifica Localização (Fina e Grossa)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionsToRequest.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        // Verifica Notificação (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        // Se alguma permissão estiver faltando, solicita
        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // Todas as permissões já estavam concedidas!
            Log.d(TAG, "Todas as permissões necessárias já concedidas. Iniciando serviço.")
            startVolumeMonitoringService() // Inicia o serviço diretamente
        }
    }
    // Função para obter a localização atual
    @SuppressLint("MissingPermission")
    private fun obterLocalizacaoAtual(callback: (Location?) -> Unit) {
        val hasFineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocationPermission && !hasCoarseLocationPermission) {
            Log.w(TAG, "Permissão de localização não concedida ao tentar obter localização.")
            Toast.makeText(this, "Permissão de localização não concedida.", Toast.LENGTH_SHORT).show()
            callback(null)
            return
        }

        Log.d(TAG, "Tentando obter localização atual (lastLocation)...")
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (location != null) {
                    Log.d(TAG, "Localização obtida (lastLocation): Lat=${location.latitude}, Lon=${location.longitude}")
                    callback(location)
                } else {
                    Log.w(TAG, "Falha ao obter localização (lastLocation nulo). Tentando getCurrentLocation...")
                    fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        CancellationTokenSource().token
                    ).addOnSuccessListener { loc: Location? ->
                        if (loc != null) {
                            Log.d(TAG, "Localização obtida (getCurrentLocation): Lat=${loc.latitude}, Lon=${loc.longitude}")
                            callback(loc)
                        } else {
                            Log.w(TAG, "Falha ao obter localização (getCurrentLocation também nulo).")
                            callback(null)
                        }
                    }.addOnFailureListener { exception ->
                        Log.e(TAG, "Erro ao obter localização (getCurrentLocation)", exception)
                        callback(null)
                    }
                }
            }
            .addOnFailureListener { exception ->
                Log.e(TAG, "Erro ao obter localização (lastLocation)", exception)
                callback(null)
            }
    }

    private val locationTimeoutMs = 7000L // 7 segundos de timeout para localização

    @SuppressLint("MissingPermission")
    private fun obterLocalizacaoAtualComTimeout(callback: (Location?) -> Unit) {
        val hasFineLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

        if (!hasFineLocationPermission && !hasCoarseLocationPermission) {
            Log.w(TAG, "Permissão de localização não concedida ao tentar obter localização.")
            Toast.makeText(this, "Permissão de localização não concedida.", Toast.LENGTH_SHORT).show()
            callback(null)
            return
        }

        var callbackCalled = AtomicBoolean(false)
        val mainScope = CoroutineScope(Dispatchers.Main)

        Log.d(TAG, "Tentando obter localização atual (lastLocation)...")
        fusedLocationClient.lastLocation
            .addOnSuccessListener { location: Location? ->
                if (!callbackCalled.getAndSet(true)) {
                    if (location != null) {
                        Log.d(TAG, "Localização obtida (lastLocation): Lat=${location.latitude}, Lon=${location.longitude}")
                        callback(location)
                    } else {
                        Log.w(TAG, "Falha ao obter localização (lastLocation nulo). Tentando getCurrentLocation...")
                        fusedLocationClient.getCurrentLocation(
                            Priority.PRIORITY_HIGH_ACCURACY,
                            CancellationTokenSource().token
                        ).addOnSuccessListener { loc: Location? ->
                            if (!callbackCalled.getAndSet(true)) {
                                if (loc != null) {
                                    Log.d(TAG, "Localização obtida (getCurrentLocation): Lat=${loc.latitude}, Lon=${loc.longitude}")
                                    callback(loc)
                                } else {
                                    Log.w(TAG, "Falha ao obter localização (getCurrentLocation também nulo).")
                                    callback(null)
                                }
                            }
                        }.addOnFailureListener { exception ->
                            if (!callbackCalled.getAndSet(true)) {
                                Log.e(TAG, "Erro ao obter localização (getCurrentLocation)", exception)
                                callback(null)
                            }
                        }
                    }
                }
            }
            .addOnFailureListener { exception ->
                if (!callbackCalled.getAndSet(true)) {
                    Log.e(TAG, "Erro ao obter localização (lastLocation)", exception)
                    callback(null)
                }
            }

        // Timeout manual para garantir que o callback sempre será chamado
        mainScope.launch {
            delay(locationTimeoutMs)
            if (!callbackCalled.getAndSet(true)) {
                Log.w(TAG, "Timeout ao tentar obter localização. Seguindo sem localização.")
                callback(null)
            }
        }
    }

    // --- Fim da Lógica de Permissões ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        // Chama a verificação de permissões ao criar a Activity
        checkAndRequestPermissions()

        setContent {
            VolumeSOSAppTheme {
                SOSScreen(
                    onSosButtonClick = {
                        triggerSosAction()

                    }

                )
            }
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        // Cancela qualquer pedido de localização pendente ao destruir a Activity
        cancellationTokenSource.cancel()
    }
    // Função para enviar o SMS com (ou sem) a localização
    private fun enviarSmsComLocalizacao(context: Context, location: Location?) {
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
                Log.d(TAG, "Tentando enviar SMS para $numeroDestino")
                val smsManager = context.getSystemService(SmsManager::class.java)
                val parts = smsManager.divideMessage(mensagemFinal)
                smsManager.sendMultipartTextMessage(numeroDestino, null, parts, null, null)
                Toast.makeText(context, "SMS de SOS enviado!", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "SMS multipart enviado para $numeroDestino")
            } catch (e: Exception) {
                Toast.makeText(context, "Erro ao enviar SMS: ${e.message}", Toast.LENGTH_LONG).show()
                Log.e(TAG, "Erro ao enviar SMS", e)
            }
        } else {
            Toast.makeText(context, "Permissão de SMS não concedida. Não é possível enviar.", Toast.LENGTH_LONG).show()
            Log.w(TAG, "Tentativa de enviar SMS sem permissão.")
        }
    }
    // Função centralizada para acionar o SOS
    private fun triggerSosAction() {
        Log.d(TAG, "Ação SOS acionada! Iniciando processo...")
        Toast.makeText(this, "Acionando SOS...", Toast.LENGTH_SHORT).show()
        obterLocalizacaoAtualComTimeout { location ->
            enviarSmsComLocalizacao(this, location)
        }
    }
    // Método chamado quando uma tecla física é pressionada
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        // Verifica se a tecla pressionada foi a de Aumentar Volume
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val currentTime = System.currentTimeMillis()

            // Lógica para contar os pressionamentos rápidos:
            // Se for o primeiro pressionamento OU se passou muito tempo desde o último...
            if (lastVolumeUpKeyPressTime == 0L || (currentTime - lastVolumeUpKeyPressTime) > MAX_PRESS_INTERVAL_MS) {
                volumeUpKeyPressCount = 1 // ...começa (ou recomeça) a contagem.
                Log.d(TAG, "Volume Up: Nova sequência. Contagem: $volumeUpKeyPressCount")
            } else {
                // Senão, incrementa a contagem se estiver dentro do intervalo de tempo.
                volumeUpKeyPressCount++
                Log.d(TAG, "Volume Up: Contagem incrementada: $volumeUpKeyPressCount")
            }

            // Atualiza o tempo do último pressionamento
            lastVolumeUpKeyPressTime = currentTime

            // Verifica se atingiu o número necessário de pressionamentos
            if (volumeUpKeyPressCount == REQUIRED_PRESSES) {
                Log.d(TAG, "$REQUIRED_PRESSES pressões de Volume Up detectadas. Acionando SOS...")
                // Chama a mesma função que o botão chama!
                triggerSosAction()
                // Reseta a contagem para a próxima sequência
                volumeUpKeyPressCount = 0
                lastVolumeUpKeyPressTime = 0L
            }

            // Retorna 'true' para indicar que consumimos o evento.
            // Isso evita que o volume realmente aumente E que o sistema
            // passe o evento para outros apps.
            return true
        } else {
            // Se qualquer outra tecla for pressionada, reseta a contagem de volume.
            if (volumeUpKeyPressCount > 0) {
                Log.d(TAG, "Outra tecla pressionada. Resetando contagem de Volume Up.")
                volumeUpKeyPressCount = 0
                lastVolumeUpKeyPressTime = 0L
            }
        }

        // Para qualquer outra tecla, deixa o sistema tratar normalmente.
        return super.onKeyDown(keyCode, event)
    }
    private fun startVolumeMonitoringService() {
        // Verifica se a permissão essencial de SMS foi concedida
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permissão de SMS não concedida. Serviço não será iniciado.")
            Toast.makeText(this, "Permissão de SMS necessária para iniciar o serviço SOS.", Toast.LENGTH_LONG).show()
            return // Não inicia o serviço sem permissão de SMS
        }

        // Verifica permissão de notificação (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Permissão de notificação não concedida (Android 13+). A notificação do serviço pode não aparecer.")
            // O serviço ainda pode iniciar, mas o usuário não verá a notificação.
            // Idealmente, já pedimos essa permissão antes.
        }

        val serviceIntent = Intent(this, VolumeMonitoringService::class.java)
        // Você pode passar dados extras para o serviço aqui, se necessário:
        // serviceIntent.putExtra("chave", "valor")

        Log.d(TAG, "Tentando iniciar o VolumeMonitoringService em foreground...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent) // Necessário para Android 8+
        } else {
            startService(serviceIntent)
        }
    }
}

// ... (O restante do código da SOSScreen e Preview continua igual) ...

@Composable
fun SOSScreen(onSosButtonClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize() // Ocupa a tela inteira
            .padding(32.dp), // Adiciona um espaçamento nas bordas
        verticalArrangement = Arrangement.Center, // Centraliza verticalmente
        horizontalAlignment = Alignment.CenterHorizontally // Centraliza horizontalmente
    ) {
        Text(
            text = "Pressione o botão abaixo ou Aumentar Volume 3x para enviar SOS.",
            style = MaterialTheme.typography.bodyLarge // Estilo do texto
        )
        Spacer(modifier = Modifier.height(24.dp)) // Espaço vertical
        Button(onClick = onSosButtonClick) { // Botão
            Text("ENVIAR SOS")
        }
    }
}

// Preview para visualizar a tela no Android Studio (opcional)
@Preview(showBackground = true)
@Composable
fun SOSScreenPreview() {
    VolumeSOSAppTheme {
        SOSScreen(onSosButtonClick = {})
    }
}
