package com.piru.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.data.SyncClient
import kotlinx.coroutines.launch

/**
 * Account + friends + leaderboard. Data syncs to ahura.site/piru-api so a dose
 * log survives a phone change or an accidental uninstall, and a friend circle
 * can compete on who takes their meds on time the most.
 */
@Composable
fun SocialScreen(state: PiruState) {
    val scope = rememberCoroutineScope()
    val client = remember { SyncClient(state.repo) }
    var mode by remember { mutableStateOf(if (client.signedIn) "home" else "auth") }
    var email by remember { mutableStateOf(state.repo.getSetting("auth_email") ?: "") }
    var pass by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    var myCode by remember { mutableStateOf(state.repo.getSetting("my_code") ?: "") }
    var friends by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var leaders by remember { mutableStateOf<List<Triple<String, String, Double>>>(emptyList()) }
    var syncBusy by remember { mutableStateOf(false) }

    fun refreshAll() {
        scope.launch {
            client.myCode().onSuccess {
                myCode = it; state.repo.setSetting("my_code", it)
            }
            client.friends().onSuccess { friends = it }
            client.leaderboard().onSuccess { leaders = it }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Account & Friends", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            "Log in so your data syncs to the server and survives switching phones or deleting the app. " +
            "Add friends with their code; whoever takes their meds on time the most rises on the leaderboard.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (mode == "auth") {
            OutlinedTextField(value = email, onValueChange = { email = it },
                label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = pass, onValueChange = { pass = it }, label = { Text("Password") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row {
                Button(onClick = {
                    scope.launch {
                        client.signUp(email, pass, email.substringBefore("@")).fold(
                            onSuccess = { state.repo.setSetting("auth_email", email); msg = null; mode = "home"; refreshAll() },
                            onFailure = { msg = it.message },
                        )
                    }
                }) { Text("Sign up") }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(onClick = {
                    scope.launch {
                        client.logIn(email, pass).fold(
                            onSuccess = { state.repo.setSetting("auth_email", email); msg = null; mode = "home"; refreshAll() },
                            onFailure = { msg = it.message },
                        )
                    }
                }) { Text("Log in") }
            }
        } else {
            PiruCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Signed in", style = MaterialTheme.typography.titleSmall)
                        Text("your code: ${if (myCode.isBlank()) "…" else myCode}", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        client.signOut(); state.repo.setSetting("auth_email", "")
                        state.repo.setSetting("my_code", ""); mode = "auth"
                    }) { Text("Sign out") }
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            syncBusy = true
                            client.pull().onSuccess { state.refresh() }
                            client.push().onSuccess { msg = "Synced." }
                                .onFailure { msg = it.message }
                            refreshAll(); syncBusy = false
                        }
                    }) { Text(if (syncBusy) "Syncing…" else "Push + pull now") }
                    OutlinedButton(onClick = { refreshAll() }) { Text("Refresh") }
                }
            }
            Spacer(Modifier.height(8.dp))

            PiruCard {
                Text("Add a friend", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("6-char code") },
                        singleLine = true, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        scope.launch {
                            client.addFriendByCode(code).fold(
                                onSuccess = { code = ""; refreshAll(); msg = "Added." },
                                onFailure = { msg = it.message },
                            )
                        }
                    }) { Text("Add") }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text("Friends (${friends.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 4.dp))
            if (friends.isEmpty()) Text("No friends yet. Share your code above.", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            friends.forEach { (id, name) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, modifier = Modifier.weight(1f), fontSize = 14.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Weekly leaderboard", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("Doses taken vs scheduled this week.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            if (leaders.isEmpty()) Text("No entries yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            leaders.forEachIndexed { i, (id, name, score) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", modifier = Modifier.width(28.dp), fontWeight = FontWeight.Bold,
                        color = if (id == leaders.firstOrNull()?.first) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(if (client.signedIn && id == myCode) "$name (you)" else name,
                        modifier = Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(if (score <= 1.5) "%.0f%%".format(score * 100) else score.toInt().toString(),
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            }
        }

        msg?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** Card linking into the social screen from Insights. */
@Composable
fun SocialCard(onOpen: () -> Unit) {
    PiruCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Account & friends", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Sync your journal; compete with friends on adherence", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("›", fontSize = 22.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
