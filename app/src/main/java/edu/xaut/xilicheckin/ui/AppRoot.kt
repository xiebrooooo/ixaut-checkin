package edu.xaut.xilicheckin.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import edu.xaut.xilicheckin.ui.accounts.AccountsScreen
import edu.xaut.xilicheckin.ui.accounts.AccountsViewModel
import edu.xaut.xilicheckin.ui.history.HistoryScreen
import edu.xaut.xilicheckin.ui.history.HistoryViewModel
import edu.xaut.xilicheckin.ui.sign.SignScreen
import edu.xaut.xilicheckin.ui.sign.SignViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val signVm: SignViewModel = hiltViewModel()
    val accountsVm: AccountsViewModel = hiltViewModel()
    val historyVm: HistoryViewModel = hiltViewModel()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("i西理签到") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                    label = { Text("签到") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Icon(Icons.Filled.People, contentDescription = null) },
                    label = { Text("账号") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Icon(Icons.Filled.History, contentDescription = null) },
                    label = { Text("记录") },
                )
            }
        },
    ) { pad ->
        CaptchaHost()
        Box(Modifier.fillMaxSize().padding(pad)) {
            when (tab) {
                0 -> SignScreen(signVm)
                1 -> AccountsScreen(accountsVm)
                else -> HistoryScreen(historyVm)
            }
        }
    }
}
