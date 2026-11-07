package de.kango99w.orbitadmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** One visible compact filter row; only expanded controls consume list height. */
@Composable
fun AdminFilterFold(
    label: String,
    expanded: Boolean,
    activeSummary: String,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        modifier=Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=4.dp),
        colors=CardDefaults.cardColors(
            containerColor=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.46f))
    ) {
        Column(modifier=Modifier.padding(horizontal=10.dp,vertical=4.dp)) {
            Row(
                modifier=Modifier.fillMaxWidth(),
                horizontalArrangement=Arrangement.SpaceBetween,
                verticalAlignment=Alignment.CenterVertically
            ) {
                Column(modifier=Modifier.weight(1f).padding(start=5.dp)) {
                    Text(label,style=MaterialTheme.typography.labelLarge)
                    Text(activeSummary.take(100),maxLines=1,
                        style=MaterialTheme.typography.labelSmall,
                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick=onToggle) {
                    Text(if(expanded)"收起 ▴" else "筛选 ▾")
                }
            }
            if(expanded) {
                Column(modifier=Modifier.padding(bottom=8.dp),
                    verticalArrangement=Arrangement.spacedBy(4.dp)) { content() }
            }
        }
    }
}
