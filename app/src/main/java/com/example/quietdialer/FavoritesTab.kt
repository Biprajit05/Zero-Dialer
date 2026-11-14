package com.example.quietdialer

import android.Manifest
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun FavoritesTab(contacts: List<Contact>, permsVersion: Int, onRequestPerms: () -> Unit) {
    val ctx = LocalContext.current
    val canRead = remember(permsVersion) { hasPerm(ctx, Manifest.permission.READ_CONTACTS) }
    val favs = remember(contacts) { contacts.filter { it.starred }.distinctBy { it.id } }

    Column(Modifier.fillMaxSize()) {
        Header("Favorites")
        when {
            !canRead -> PermissionPrompt("Allow contacts access to see your favorites here.", onRequestPerms)
            favs.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No favorites yet.\nStar a contact in your contacts app and it will show up here.",
                    color = Pal.Gray, textAlign = TextAlign.Center
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(favs, key = { it.id }) { c ->
                    Column(
                        Modifier.padding(4.dp).clip(RoundedCornerShape(16.dp))
                            .clickable { placeCall(ctx, c.number) }.padding(vertical = 12.dp, horizontal = 4.dp)
                            .fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Avatar(c.name, 76.dp)
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
}
