package com.gatherin.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gatherin.MainViewModel
import com.gatherin.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(vm: MainViewModel) {
    val authLoading by vm.authLoading.collectAsStateWithLifecycle()
    val authError   by vm.authError.collectAsStateWithLifecycle()

    var isLogin   by remember { mutableStateOf(true) }
    var email     by remember { mutableStateOf("organizer@example.com") }
    var password  by remember { mutableStateOf("password123") }
    var fullName  by remember { mutableStateOf("Alex Organizer") }
    var role      by remember { mutableStateOf("organizer") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(listOf(NavyDeep, NavyDark, Color(0xFF1A2B4A)))
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp)
                .padding(top = 48.dp),
        ) {
            // ── Brand Badge ─────────────────────────────────────────────────
            Surface(
                shape = RoundedCornerShape(50),
                color = BrandBlue,
                modifier = Modifier.wrapContentSize()
            ) {
                Text(
                    text = "🎟 Gatherin",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Hero Text ───────────────────────────────────────────────────
            Text(
                text  = "Events, without\nthe friction.",
                style = MaterialTheme.typography.displayLarge,
                color = Color.White
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text  = "Welcome attendees instantly with fast QR scanning and live room statistics.",
                style = MaterialTheme.typography.bodyLarge,
                color = SlateGray
            )

            Spacer(Modifier.height(28.dp))

            // ── Auth Card ───────────────────────────────────────────────────
            Card(
                shape  = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {

                    // Tab toggle
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(4.dp)) {
                            TabButton("Sign In",       isLogin,  Modifier.weight(1f)) { isLogin = true }
                            TabButton("Create Account", !isLogin, Modifier.weight(1f)) { isLogin = false }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    // Register-only fields
                    AnimatedVisibility(
                        visible = !isLogin,
                        enter   = expandVertically(),
                        exit    = shrinkVertically()
                    ) {
                        Column {
                            AuthLabel("Full Name")
                            AuthTextField(
                                value         = fullName,
                                onValueChange = { fullName = it },
                                placeholder   = "Alex Morgan"
                            )
                            Spacer(Modifier.height(14.dp))
                        }
                    }

                    AuthLabel("Email Address")
                    AuthTextField(
                        value           = email,
                        onValueChange   = { email = it },
                        placeholder     = "you@example.com",
                        keyboardType    = KeyboardType.Email
                    )

                    Spacer(Modifier.height(14.dp))

                    AuthLabel("Password")
                    AuthTextField(
                        value           = password,
                        onValueChange   = { password = it },
                        placeholder     = "At least 8 characters",
                        visualTransformation = PasswordVisualTransformation(),
                        imeAction       = if (isLogin) ImeAction.Done else ImeAction.Next
                    )

                    // Role picker (register only)
                    AnimatedVisibility(
                        visible = !isLogin,
                        enter   = expandVertically(),
                        exit    = shrinkVertically()
                    ) {
                        Column {
                            Spacer(Modifier.height(14.dp))
                            AuthLabel("I am joining as")
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                RoleChip("Organizer", role == "organizer", Modifier.weight(1f)) { role = "organizer" }
                                RoleChip("Attendee",  role == "attendee",  Modifier.weight(1f)) { role = "attendee" }
                            }
                        }
                    }

                    // Error
                    authError?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text  = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            modifier  = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    Button(
                        onClick = {
                            if (isLogin) vm.login(email, password)
                            else vm.register(email, password, fullName, role)
                        },
                        enabled  = !authLoading,
                        shape    = RoundedCornerShape(12.dp),
                        colors   = ButtonDefaults.buttonColors(containerColor = BrandBlue),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        if (authLoading) {
                            CircularProgressIndicator(
                                color    = Color.White,
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text  = if (isLogin) "Sign In →" else "Create Account →",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(40.dp))
        }
    }
}

// ── Reusable sub-components ───────────────────────────────────────────────────

@Composable
private fun TabButton(label: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick   = onClick,
        shape     = RoundedCornerShape(8.dp),
        color     = if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
        modifier  = modifier
    ) {
        Text(
            text      = label,
            style     = MaterialTheme.typography.labelLarge,
            color     = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier  = Modifier.padding(vertical = 10.dp)
        )
    }
}

@Composable
private fun AuthLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 6.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    imeAction: ImeAction = ImeAction.Next
) {
    OutlinedTextField(
        value         = value,
        onValueChange = onValueChange,
        placeholder   = { Text(placeholder, color = SlateGray) },
        singleLine    = true,
        shape         = RoundedCornerShape(10.dp),
        colors        = OutlinedTextFieldDefaults.colors(
            focusedTextColor     = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor   = MaterialTheme.colorScheme.onSurface,
            focusedBorderColor   = BrandBlue,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            cursorColor          = BrandBlue,
            // Explicit solid container color matching the theme surface so the
            // typed text is never lost against a transparent/contrast-missing
            // field on the emulator.
            focusedContainerColor     = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor   = MaterialTheme.colorScheme.surface
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction    = imeAction
        ),
        visualTransformation = visualTransformation,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun RoleChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape   = RoundedCornerShape(10.dp),
        colors  = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) BrandBlue else Color.Transparent,
            contentColor   = if (selected) Color.White else MaterialTheme.colorScheme.onSurface
        ),
        border  = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = if (selected) BrandBlue else MaterialTheme.colorScheme.outline
        ),
        modifier = modifier
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

// ── Compose Previews ──────────────────────────────────────────────────────────

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Light Mode Preview")
@Composable
fun AuthScreenLightPreview() {
    GatherinTheme(darkTheme = false) {
        AuthScreen(vm = MainViewModel())
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, name = "Dark Mode Preview")
@Composable
fun AuthScreenDarkPreview() {
    GatherinTheme(darkTheme = true) {
        AuthScreen(vm = MainViewModel())
    }
}
