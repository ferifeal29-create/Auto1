package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Phishing
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@Composable
fun GuideScreen(
    isAccessibilityEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 720.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Panduan & Transparansi",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Ikuti langkah berikut agar otomatisasi memancing Gridmere berjalan presisi.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            item {
                GuideStepCard(
                    stepNumber = "1",
                    title = "Aktifkan Accessibility Service",
                    description = "AutoFish menggunakan Android AccessibilityService untuk mengirim ketukan layar pada posisi Cast, kontrol minigame, serta tombol Sell/Keep hanya saat status START aktif.",
                    icon = Icons.Outlined.AccessibilityNew,
                    actionLabel = if (isAccessibilityEnabled) "Sudah Aktif" else "Buka Pengaturan Aksesibilitas",
                    actionEnabled = !isAccessibilityEnabled,
                    onActionClick = onOpenAccessibilitySettings
                )
            }

            item {
                GuideStepCard(
                    stepNumber = "2",
                    title = "Sesuaikan Kalibrasi Koordinat",
                    description = "Buka tab Kalibrasi (Anda juga bisa memuat screenshot Gridmere dari galeri) dan pastikan pin Umpan Cacing, Blok Air, Panah Bawah (↓), Panah Kiri/Kanan (←/→), serta tombol Sell/Keep berada tepat di posisi layar Anda.",
                    icon = Icons.Outlined.Tune
                )
            }

            item {
                GuideStepCard(
                    stepNumber = "3",
                    title = "Izinkan Screen Capture (MediaProjection)",
                    description = "Saat menekan START, izinkan tangkapan layar lalu segera pindah ke aplikasi Gridmere (jeda awal 7 detik). AutoFish otomatis menyesuaikan resolusi landscape.",
                    icon = Icons.Outlined.Cast
                )
            }

            item {
                GuideStepCard(
                    stepNumber = "4",
                    title = "Alur Otomatisasi 3 Fase Gridmere",
                    description = "Fase 1: AutoFish memilih umpan cacing lalu mengetuk blok air untuk melempar kail. Fase 2: Saat bar hitam miring muncul (ikan menyambar), bot menekan panah bawah (↓). Fase 3: Bot mengarahkan kotak hijau/kuning mengikuti ikan dengan ←/→ secara adaptif, lalu menekan panah bawah (↓) lagi begitu bar biru muda penuh, kemudian menekan Sell/Keep.",
                    icon = Icons.Outlined.Phishing
                )
            }

            item {
                GuideStepCard(
                    stepNumber = "5",
                    title = "Privasi & Kontrol Penuh",
                    description = "Seluruh analisis Bitmap dari ImageReader diproses secara lokal di memori perangkat dan langsung dibersihkan. Anda dapat menekan STOP kapan saja dari aplikasi atau notifikasi.",
                    icon = Icons.Outlined.Security
                )
            }
        }
    }
}

@Composable
private fun GuideStepCard(
    stepNumber: String,
    title: String,
    description: String,
    icon: ImageVector,
    actionLabel: String? = null,
    actionEnabled: Boolean = true,
    onActionClick: (() -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Text(
                    text = "$stepNumber. $title",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (actionLabel != null && onActionClick != null) {
                Button(
                    onClick = onActionClick,
                    enabled = actionEnabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}
