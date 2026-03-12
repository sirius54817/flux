/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.compose.browser.toolbar

import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mozilla.components.browser.state.selector.selectedTab
import mozilla.components.lib.state.observeAsState
import org.mozilla.reference.browser.compose.browserStore
import org.mozilla.reference.browser.compose.searchUseCases
import org.mozilla.reference.browser.compose.sessionUseCases

// ── shadcn/ui zinc dark tokens ──
private val Background  = Color(0xFF09090B) // zinc-950
private val Card        = Color(0xFF18181B) // zinc-900
private val Input       = Color(0xFF27272A) // zinc-800
private val Border      = Color(0xFF3F3F46) // zinc-700
private val Foreground  = Color(0xFFFAFAFA) // zinc-50
private val Muted       = Color(0xFFA1A1AA) // zinc-400
private val Placeholder = Color(0xFF71717A) // zinc-500
private val UrlBoxShape = RoundedCornerShape(6.dp)

@Composable
fun BrowserToolbar() {
    val url: String? by browserStore().observeAsState { state -> state.selectedTab?.content?.url }
    val editMode = remember { mutableStateOf(false) }
    val sessionUC = sessionUseCases()
    val searchUC = searchUseCases()

    if (editMode.value) {
        BrowserEditToolbar(
            url = url ?: "",
            onUrlCommitted = { text ->
                val trimmed = text.trim()
                val isUrl = Patterns.WEB_URL.matcher(trimmed).matches() ||
                    trimmed.startsWith("http://") ||
                    trimmed.startsWith("https://") ||
                    trimmed.startsWith("about:")
                if (isUrl) {
                    val urlToLoad = if (trimmed.startsWith("http")) trimmed else "https://$trimmed"
                    sessionUC.loadUrl(urlToLoad)
                } else {
                    searchUC.defaultSearch(trimmed)
                }
                editMode.value = false
            },
        )
    } else {
        BrowserDisplayToolbar(
            url = url ?: "",
            onUrlClicked = { editMode.value = true },
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF09090B, name = "Display Toolbar")
@Composable
fun BrowserDisplayToolbarPreview() {
    BrowserDisplayToolbar(url = "https://www.mozilla.org")
}

@Composable
fun BrowserDisplayToolbar(
    url: String,
    onUrlClicked: () -> Unit = {},
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(UrlBoxShape)
            .background(Input)
            .border(width = 1.dp, color = Border, shape = UrlBoxShape)
            .clickable { onUrlClicked() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = url.ifEmpty { "Search or enter address" },
            style = TextStyle(
                fontSize = 14.sp,
                color = if (url.isEmpty()) Placeholder else Muted,
            ),
            maxLines = 1,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF09090B, name = "Edit Toolbar")
@Composable
fun BrowserEditToolbarPreview() {
    BrowserEditToolbar(url = "https://www.mozilla.org")
}

@Composable
fun BrowserEditToolbar(
    url: String,
    onUrlCommitted: (String) -> Unit = {},
) {
    var input by remember { mutableStateOf(url) }

    TextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(UrlBoxShape)
            .border(width = 1.dp, color = Border, shape = UrlBoxShape),
        singleLine = true,
        textStyle = TextStyle(fontSize = 14.sp, color = Foreground),
        placeholder = {
            Text(
                "Search or enter address",
                style = TextStyle(fontSize = 14.sp, color = Placeholder),
            )
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Input,
            unfocusedContainerColor = Input,
            focusedTextColor = Foreground,
            unfocusedTextColor = Foreground,
            cursorColor = Foreground,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(
            onGo = { onUrlCommitted(input) },
        ),
    )
}
