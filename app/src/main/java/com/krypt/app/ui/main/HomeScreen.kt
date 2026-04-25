package com.krypt.app.ui.main

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.krypt.app.R
import com.krypt.app.ui.home.HomeViewModel
import com.krypt.app.ui.home.InstalledAppRow
import com.krypt.app.ui.home.UserMessage
import kotlinx.coroutines.flow.consumeAsFlow

/**
 * Primary Home Screen: a searchable, scrollable list of all installed
 * non-system apps with a one-way lock toggle per row (feature 002).
 *
 * Toggle ON: locks the app instantly (no Guardian required, FR-032).
 * Toggle OFF attempted: does NOT unlock; dispatches a Guardian request URL
 * via the system share sheet instead (FR-033).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(lifecycleOwner) { viewModel.refresh() }

    LaunchedEffect(Unit) {
        viewModel.shareIntents.consumeAsFlow().collect { intent ->
            ctx.startActivity(
                Intent.createChooser(intent, ctx.getString(R.string.home_share_chooser_title))
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.userMessages.consumeAsFlow().collect { msg ->
            when (msg) {
                UserMessage.MasterKeyMissing ->
                    snackbarHostState.showSnackbar("PIN not set up yet. Complete setup first.")
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::setQuery,
                placeholder = { Text(stringResource(R.string.home_search_hint)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (state.rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (state.query.isBlank())
                            stringResource(R.string.home_empty_loading)
                        else
                            stringResource(R.string.home_empty_no_match, state.query),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(
                        items = state.rows,
                        key = { it.packageName },
                    ) { row ->
                        InstalledAppRow(
                            state = row,
                            iconCache = viewModel.iconCache,
                            onToggle = { viewModel.onToggle(row) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}
