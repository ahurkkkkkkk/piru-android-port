package com.piru.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.piru.app.models.SubstanceCategory
import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.launch

/** Server-served DSM-lite condition blurbs; observable so sheets recompose when it lands. */
val conditionsCache = mutableStateMapOf<com.piru.app.data.AppRepository, List<Pair<String, String>>>()

@Composable
fun loadConditions(repo: com.piru.app.data.AppRepository) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(repo) {
        if (conditionsCache.containsKey(repo)) return@LaunchedEffect
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val conn = java.net.URL("https://ahura.site/piru-api/api/conditions").openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 5_000; conn.readTimeout = 8_000
                val arr = org.json.JSONArray(conn.inputStream.bufferedReader().readText())
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    o.getString("text") to o.getString("blurb")
                }
            }.getOrDefault(emptyList())
        }
        if (loaded.isNotEmpty()) conditionsCache[repo] = loaded
    }
}

/**
 * Condition screen (bottom sheet). Search a condition/indication text and list
 * every substance whose label says it's used for that condition. Tap to open the
 * substance detail.
 */
@Composable
fun ConditionSheet(state: PiruState, condition: String, onOpenSubstance: (String) -> Unit) {
    loadConditions(state.repo)
    val store = state.store
    val subs = remember(condition, store) { store?.substancesForCondition(condition) ?: emptyList() }
    Column(
        Modifier.fillMaxWidth().heightIn(max = 520.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(condition, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            "${subs.size} substance${if (subs.size == 1) "" else "s"} with this indication on their label",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val blurb = remember(condition) { conditionsCache[state.repo]?.firstOrNull { condition.lowercase().contains(it.first.lowercase()) }?.second }
        if (blurb != null) {
            Spacer(Modifier.height(8.dp))
            Text("What this is", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(blurb, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, lineHeight = with(androidx.compose.ui.unit.TextUnit) { 18.sp })
        }
        Spacer(Modifier.height(10.dp))
        if (subs.isEmpty()) {
            Text("No substances found for this condition.", fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            subs.forEach { sub ->
                val s = remember(sub.id) { store?.substanceByID(sub.id) }
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenSubstance(sub.name) }.padding(vertical = 10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.name, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        s?.let {
                            Text(
                                it.category.label,
                                fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
            }
        }
    }
}

/** A "DSM-like" condition entry: a plain-language explainer the app ships for the
 *  common indication texts. Falls back to a stub for anything not cataloged. */
data class ConditionInfo(val text: String, val blurb: String, val classHint: String? = null)

object Conditions {
    val catalog = mapOf(
        "anxiety" to "Anxiety is persistent worry, tension, or fear that interferes with daily life. Substances used 'for anxiety' act on GABA-A (benzodiazepines, barbiturates, alcohol, GHB), on serotonin reuptake (SSRIs/SNRIs), on histamine/orexin (sedating antihistamines), or on alpha-2-delta (gabapentinoids). Non-addictive first-line options include SSRIs/SNRIs/buspirone; GABAergic options carry dependence and overdose risk, especially with opioids or alcohol.",
        "depression" to "Depression is persistent low mood, loss of interest, and loss of energy for 2+ weeks. It's treated with antidepressants (SSRIs, SNRIs, TCAs, MAOIs, bupropion, mirtazapine, esketamine, etc.). MAOIs carry serotonin syndrome risk with stimulants/empathogens/SSRIs - always mind the washout rules.",
        "pain" to "Pain is broadly divided into nociceptive (tissue damage), inflammatory, and neuropathic (nerve damage). Opioids and NSAIDs cover nociceptive/inflammatory pain; neuropathic pain responds better to gabapentinoids, TCAs, SNRIs, and certain stimulants (e.g. tapentadol for dual mu-agonist + NRI).",
        "adhd" to "ADHD is a neurodevelopmental condition of impaired attention and impulse control. It's treated with stimulants (amphetamine, methylphenidate) or wakefulness agents (modafinil/armodafinil) that enhance dopamine and norepinephrine. Piru's stimulant tolerance model is relevant: tachyphylaxis develops within-session and tolerance builds over months.",
        "insomnia" to "Insomnia is persistent difficulty falling or staying asleep despite opportunity. Sedatives help short-term: Z-drugs (zolpidem, zopiclone), benzodiazepines, orexin antagonists (suvorexant), trazodone, mirtazapine, melatonin, sedating antihistamines. Long-term use of GABAergic sedatives leads to tolerance and rebound insomnia.",
        "epilepsy" to "Epilepsy is a tendency to recurrent seizures. Anticonvulsants (valproate, carbamazepine, lamotrigine, levetiracetam, benzodiazepines, phenobarbital, gabapentinoids) raise seizure threshold via sodium-channel blockade, GABA enhancement, or SV2A binding.",
        "hypertension" to "High blood pressure. Treated with ACE inhibitors, ARBs, calcium-channel blockers, diuretics, and beta-blockers. Stimulants and cocaine/nicotine raise BP and are contraindicated; alpha-2 agonists (clonidine) and nitrates lower it.",
        "diabetes" to "Diabetes is impaired glucose regulation. Type 1 is autoimmune; Type 2 is insulin-resistance-driven. Treatments: insulin, metformin, GLP-1 agonists, sulfonylureas. Note that stimulants and caffeine can affect glucose control; alcohol causes delayed hypoglycemia.",
        "asthma" to "Asthma is reversible airway narrowing with inflammation. Relievers: beta-2 agonists (salbutamol/albuterol). Controllers: inhaled corticosteroids, long-acting beta-agonists, leukotriene antagonists, anticholinergics. Beta-blockers are contraindicated (cause bronchospasm).",
        "cough" to "A cough reflex mediated by airway irritation. Suppressants: dextromethorphan (NMDA/Sigma-1), codeine (mu-opioid), benzonatate (local anesthetic on vagal afferents). Expectorants (guaifenesin) thin mucus. Note dextromethorphan's abuse potential as a dissociative.",
        "migraine" to "Migraine is a neurovascular headache with sensitization and cortical spreading depolarization. Acute: triptans (5-HT1B/1D agonists), NSAIDs, CGRP antagonists. Preventive: beta-blockers, amitriptyline, valproate, topiramate, CGRP monoclonal antibodies. Ergotamine is a legacy vasoconstrictor.",
        "infection" to "Infections are treated with antimicrobials (antibiotics, antivirals, antifungals, antiparasitics). They work by inhibiting essential microbial processes: cell wall synthesis (beta-lactams), protein synthesis (tetracyclines, macrolides), DNA replication (fluoroquinolones). Resistance is a major clinical problem; adherence to full courses matters.",
        "hypothyroidism" to "Underactive thyroid. Treated with levothyroxine (synthetic T4). Note that stimulants increase heart rate and blood pressure, so caution with uncontrolled hyperthyroidism or replacement therapy.",
        "glaucoma" to "High intraocular pressure damaging the optic nerve. Treated with topical beta-blockers (timolol), prostaglandin analogs, alpha-2 agonists (brimonidine), and carbonic anhydrase inhibitors. Anticholinergics can worsen angle-closure glaucoma.",
        "nausea" to "Nausea involves the chemoreceptor trigger zone and the vomiting center. Antiemetics: dopamine antagonists (metoclopramide, prochlorperazine), 5-HT3 antagonists (ondansetron), antihistamines (dimenhydrinate), muscarinic antagonists (scopolamine). Note serotonin syndrome risk when 5-HT3 antagonists combine with MAOIs.",
        "allergy" to "Allergic reactions involve histamine and immune mediators. Treated with antihistamines (H1-blockers; diphenhydramine and hydroxyzine are sedating, loratadine and cetirizine are less so), and in severe cases epinephrine, corticosteroids.",
        "smoking cessation" to "Nicotine withdrawal causes irritability and cravings. Treated with nicotine replacement (patch/gum/lozenge), bupropion (NDRI), and varenicline (partial alpha-4-beta-2 nicotinic agonist). Note that quitting smoking can raise clozapine levels (CYP1A2 induction drops when smoking stops).",
        "weight loss" to "Obesity treatment targets appetite and energy: GLP-1 agonists (semaglutide/liraglutide), orlistat (lipase inhibitor), phentermine/topiramate (sympathomimetic + anticonvulsant). Some ADHD and diabetes drugs (stimulants, GLP-1s) are used off-label for weight loss; this carries abuse and nutritional risks.",
    )

    fun forText(text: String): ConditionInfo {
        val key = catalog.keys.firstOrNull { text.lowercase().contains(it) }
        return ConditionInfo(text, key?.let { catalog[it] } ?: "Condition: \"$text\". This substance is labeled for this indication. See the substance detail card for the full label and citation.")
    }
}
