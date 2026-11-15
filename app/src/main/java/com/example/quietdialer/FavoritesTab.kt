package com.example.quietdialer

import android.Manifest
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FavoritesTab(
    contacts: List<Contact>,
    permsVersion: Int,
    onRequestPerms: () -> Unit,
    onOpenContact: (Contact) -> Unit,
    onAddFavorite: () -> Unit
) {
    val ctx = LocalContext.current
    val canRead = remember(permsVersion) { hasPerm(ctx, Manifest.permission.READ_CONTACTS) }
    val favs = remember(contacts) { contacts.filter { it.starred }.distinctBy { it.id } }

    Box(Modifier.fillMaxSize().background(Pal.Bg)) {
        Column(Modifier.fillMaxSize()) {
            CollapsingHeader("Favorites", false)
            when {
                !canRead -> PermissionPrompt("Allow contacts access to see your favorites here.", onRequestPerms)
                favs.isEmpty() -> Box(Modifier.fillMaxSize().padding(bottom = 120.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        SmileGlyph()
                        Spacer(Modifier.height(14.dp))
                        Text("No favorite contacts", color = Pal.Ink, fontSize = 16.sp, textAlign = TextAlign.Center)
                    }
                }
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 96.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(favs, key = { it.id }) { c ->
                        Column(
                            Modifier.padding(4.dp).clip(RoundedCornerShape(16.dp))
                                .combinedClickable(onClick = { placeCall(ctx, c.number) }, onLongClick = { onOpenContact(c) })
                                .padding(vertical = 12.dp, horizontal = 4.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Avatar(c.name, 72.dp, c.id)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                c.name, fontSize = 14.sp, color = Pal.Ink, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
        GreenFab(onAddFavorite, Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }
}

/** Two dots and a smile, drawn by hand. */
@Composable
private fun SmileGlyph() {
    Canvas(Modifier.size(width = 52.dp, height = 36.dp)) {
        val r = 3.5.dp.toPx()
        drawCircle(Pal.Green, r, Offset(size.width * 0.18f, r + 1f))
        drawCircle(Pal.Gray, r, Offset(size.width * 0.82f, r + 1f))
        drawArc(
            color = Pal.Gray, startAngle = 20f, sweepAngle = 140f, useCenter = false,
            topLeft = Offset(size.width * 0.12f, size.height * 0.05f),
            size = Size(size.width * 0.76f, size.height * 0.9f),
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
