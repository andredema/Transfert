package com.zenviko.transfert

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Transfert Wi-Fi : copie les fichiers d'un dossier du téléphone vers le PC,
 * vérifie chaque copie (SHA-256 calculé des deux côtés), puis supprime
 * l'original du téléphone seulement si les deux empreintes correspondent.
 */
class MainActivity : Activity() {

    private lateinit var champAdresse: EditText
    private lateinit var champJeton: EditText
    private lateinit var champDossier: EditText
    private lateinit var champAge: EditText
    private lateinit var caseSousDossiers: CheckBox
    private lateinit var caseSupprimer: CheckBox
    private lateinit var listeDossiers: Spinner
    private lateinit var boutonTester: Button
    private lateinit var boutonPermission: Button
    private lateinit var boutonDemarrer: Button
    private lateinit var boutonArreter: Button
    private lateinit var barreProgression: ProgressBar
    private lateinit var texteStatut: TextView
    private lateinit var texteJournal: TextView

    private val arretDemande = AtomicBoolean(false)
    private val journal = ArrayDeque<String>()

    private val racineStockage: File get() = Environment.getExternalStorageDirectory()

    private val dossiersRapides = listOf(
        "— Dossiers rapides —" to "",
        "Appareil photo (DCIM/Camera)" to "DCIM/Camera",
        "Tout DCIM" to "DCIM",
        "Captures d'écran" to "Pictures/Screenshots",
        "Images (Pictures)" to "Pictures",
        "Téléchargements (Download)" to "Download",
        "Vidéos (Movies)" to "Movies",
        "Documents" to "Documents",
        "WhatsApp – médias" to "Android/media/com.whatsapp/WhatsApp/Media",
    )

    // ---------------------------------------------------------------- Cycle de vie

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        champAdresse = findViewById(R.id.champAdresse)
        champJeton = findViewById(R.id.champJeton)
        champDossier = findViewById(R.id.champDossier)
        champAge = findViewById(R.id.champAge)
        caseSousDossiers = findViewById(R.id.caseSousDossiers)
        caseSupprimer = findViewById(R.id.caseSupprimer)
        listeDossiers = findViewById(R.id.listeDossiers)
        boutonTester = findViewById(R.id.boutonTester)
        boutonPermission = findViewById(R.id.boutonPermission)
        boutonDemarrer = findViewById(R.id.boutonDemarrer)
        boutonArreter = findViewById(R.id.boutonArreter)
        barreProgression = findViewById(R.id.barreProgression)
        texteStatut = findViewById(R.id.texteStatut)
        texteJournal = findViewById(R.id.texteJournal)

        listeDossiers.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, dossiersRapides.map { it.first }
        )
        listeDossiers.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (position > 0) {
                    champDossier.setText(File(racineStockage, dossiersRapides[position].second).absolutePath)
                }
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        chargerReglages()

        boutonPermission.setOnClickListener { demanderPermission() }
        boutonTester.setOnClickListener { testerConnexion() }
        boutonDemarrer.setOnClickListener { preparerTransfert() }
        boutonArreter.setOnClickListener {
            arretDemande.set(true)
            statut("Arrêt en cours…")
        }
    }

    override fun onResume() {
        super.onResume()
        boutonPermission.visibility = if (aLaPermission()) View.GONE else View.VISIBLE
    }

    override fun onPause() {
        super.onPause()
        sauverReglages()
    }

    // ---------------------------------------------------------------- Réglages

    private fun chargerReglages() {
        val p = getSharedPreferences("reglages", MODE_PRIVATE)
        champAdresse.setText(p.getString("adresse", ""))
        champJeton.setText(p.getString("jeton", ""))
        champDossier.setText(p.getString("dossier", File(racineStockage, "DCIM/Camera").absolutePath))
        champAge.setText(p.getInt("age", 0).toString())
        caseSousDossiers.isChecked = p.getBoolean("sousDossiers", true)
        caseSupprimer.isChecked = p.getBoolean("supprimer", true)
    }

    private fun sauverReglages() {
        getSharedPreferences("reglages", MODE_PRIVATE).edit()
            .putString("adresse", champAdresse.text.toString().trim())
            .putString("jeton", champJeton.text.toString().trim())
            .putString("dossier", champDossier.text.toString().trim())
            .putInt("age", champAge.text.toString().toIntOrNull() ?: 0)
            .putBoolean("sousDossiers", caseSousDossiers.isChecked)
            .putBoolean("supprimer", caseSupprimer.isChecked)
            .apply()
    }

    // ---------------------------------------------------------------- Permission

    private fun aLaPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

    private fun demanderPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        boutonPermission.visibility = if (aLaPermission()) View.GONE else View.VISIBLE
    }

    // ---------------------------------------------------------------- Connexion

    private fun adresseServeur(): String? {
        var a = champAdresse.text.toString().trim()
            .removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (a.isEmpty()) return null
        if (!a.contains(":")) a += ":8765"
        return "http://$a"
    }

    private fun testerConnexion() {
        val base = adresseServeur() ?: return statut("Indique l'adresse du PC.")
        val jeton = champJeton.text.toString().trim()
        sauverReglages()
        statut("Connexion à $base…")
        thread {
            try {
                val c = URL("$base/ping").openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                c.setRequestProperty("X-Jeton", jeton)
                val code = c.responseCode
                val json = lireJson(c, code)
                c.disconnect()
                when {
                    code == 200 -> statut(
                        "✓ Connecté à « ${json.optString("nom")} » — " +
                            "${formater(json.optLong("espace_libre"))} libres sur le PC"
                    )
                    code == 403 -> statut("✗ Jeton incorrect.")
                    else -> statut("✗ Réponse inattendue (HTTP $code).")
                }
            } catch (e: Exception) {
                statut(
                    "✗ PC injoignable : ${e.message}\n" +
                        "Vérifie : même réseau Wi-Fi, serveur lancé, pare-feu Windows autorisé."
                )
            }
        }
    }

    // ---------------------------------------------------------------- Transfert

    private fun preparerTransfert() {
        if (!aLaPermission()) {
            statut("Autorise d'abord l'accès aux fichiers.")
            demanderPermission()
            return
        }
        val base = adresseServeur() ?: return statut("Indique l'adresse du PC.")
        val jeton = champJeton.text.toString().trim()
        val racine = File(champDossier.text.toString().trim())
        if (!racine.isDirectory) return statut("Dossier introuvable : ${racine.absolutePath}")

        val recursif = caseSousDossiers.isChecked
        val supprimer = caseSupprimer.isChecked
        val ageMin = champAge.text.toString().toIntOrNull() ?: 0
        sauverReglages()

        enCours(true)
        statut("Analyse du dossier…")
        thread {
            val fichiers = lister(racine, recursif, ageMin)
            val total = fichiers.sumOf { it.length() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (fichiers.isEmpty()) {
                    statut("Aucun fichier à transférer.")
                    enCours(false)
                    return@runOnUiThread
                }
                var message = "${fichiers.size} fichier(s) — ${formater(total)} — seront copiés vers le PC."
                if (supprimer) {
                    message += "\n\nChaque fichier sera ensuite SUPPRIMÉ du téléphone, " +
                        "uniquement si sa copie sur le PC est vérifiée identique (SHA-256)."
                }
                AlertDialog.Builder(this)
                    .setTitle("Confirmer le transfert")
                    .setMessage(message)
                    .setCancelable(false)
                    .setPositiveButton("Lancer") { _, _ -> transferer(base, jeton, racine, fichiers, total, supprimer) }
                    .setNegativeButton("Annuler") { _, _ ->
                        statut("Annulé.")
                        enCours(false)
                    }
                    .show()
            }
        }
    }

    /** Liste les fichiers (en ignorant les fichiers et dossiers cachés : .thumbnails, .trashed…). */
    private fun lister(racine: File, recursif: Boolean, ageMinJours: Int): List<File> {
        val limite = if (ageMinJours > 0) System.currentTimeMillis() - ageMinJours * 86_400_000L else Long.MAX_VALUE
        val resultat = ArrayList<File>()
        fun parcourir(dossier: File) {
            val enfants = dossier.listFiles() ?: return
            for (f in enfants) {
                if (f.name.startsWith(".")) continue
                if (f.isDirectory) {
                    if (recursif) parcourir(f)
                } else if (f.isFile && f.lastModified() <= limite) {
                    resultat.add(f)
                }
            }
        }
        parcourir(racine)
        resultat.sortBy { it.lastModified() }
        return resultat
    }

    private fun transferer(
        base: String, jeton: String, racine: File, fichiers: List<File>, total: Long, supprimer: Boolean
    ) {
        arretDemande.set(false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        journal.clear()
        // Sur le PC : <Marque Modèle>/<nom du dossier>/<sous-dossiers>/fichier
        val prefixe = "${Build.MANUFACTURER} ${Build.MODEL}".trim() + "/" + racine.name

        thread {
            var reussis = 0
            var supprimes = 0
            var erreurs = 0
            var erreursReseauDeSuite = 0
            var octetsFaits = 0L
            var dernierAffichage = 0L
            val debut = SystemClock.elapsedRealtime()

            for ((i, f) in fichiers.withIndex()) {
                if (arretDemande.get()) break
                val taille = f.length()
                val relatif = prefixe + "/" + f.relativeTo(racine).path.replace(File.separatorChar, '/')
                statut("(${i + 1}/${fichiers.size}) ${f.name}")

                try {
                    val doublon = envoyer(base, jeton, f, relatif) { envoyes ->
                        val maintenant = SystemClock.elapsedRealtime()
                        if (maintenant - dernierAffichage > 250) {
                            dernierAffichage = maintenant
                            progression(octetsFaits + envoyes, total)
                        }
                    }
                    reussis++
                    erreursReseauDeSuite = 0
                    var ligne = (if (doublon) "= " else "✓ ") + f.name
                    if (supprimer) {
                        if (f.delete()) {
                            supprimes++
                            MediaScannerConnection.scanFile(this, arrayOf(f.absolutePath), null, null)
                            ligne += "  (supprimé)"
                        } else {
                            ligne += "  ⚠ copié mais suppression impossible"
                        }
                    }
                    log(ligne)
                } catch (e: InterruptedException) {
                    log("■ ${f.name} : interrompu, conservé sur le téléphone")
                    break
                } catch (e: Exception) {
                    erreurs++
                    log("✗ ${f.name} : ${e.message} — conservé sur le téléphone")
                    if (e is ConnectException || e is SocketTimeoutException) {
                        erreursReseauDeSuite++
                        if (erreursReseauDeSuite >= 3) {
                            log("Connexion au PC perdue : arrêt.")
                            break
                        }
                    }
                }
                octetsFaits += taille
                progression(octetsFaits, total)
            }

            val duree = (SystemClock.elapsedRealtime() - debut) / 1000
            val bilan = buildString {
                append(if (arretDemande.get()) "Arrêté. " else "Terminé. ")
                append("$reussis copié(s)")
                if (supprimer) append(", $supprimes supprimé(s)")
                if (erreurs > 0) append(", $erreurs erreur(s)")
                append(" — ${formater(octetsFaits)} en ${duree / 60} min ${duree % 60} s")
            }
            statut(bilan)
            runOnUiThread {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                enCours(false)
            }
        }
    }

    /**
     * Envoie un fichier et vérifie l'empreinte renvoyée par le PC.
     * Retourne true si le fichier était déjà présent (doublon), lève une exception en cas d'échec.
     */
    private fun envoyer(base: String, jeton: String, f: File, relatif: String, avance: (Long) -> Unit): Boolean {
        val taille = f.length()
        val c = URL("$base/upload").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.doOutput = true
            c.useCaches = false
            c.connectTimeout = 5000
            c.readTimeout = 120_000
            c.setFixedLengthStreamingMode(taille)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("X-Jeton", jeton)
            c.setRequestProperty("X-Chemin", URLEncoder.encode(relatif, "UTF-8"))
            c.setRequestProperty("X-Modifie", f.lastModified().toString())

            val md = MessageDigest.getInstance("SHA-256")
            val tampon = ByteArray(256 * 1024)
            var envoyes = 0L
            FileInputStream(f).use { entree ->
                c.outputStream.use { sortie ->
                    while (true) {
                        if (arretDemande.get()) throw InterruptedException()
                        val lu = entree.read(tampon)
                        if (lu < 0) break
                        sortie.write(tampon, 0, lu)
                        md.update(tampon, 0, lu)
                        envoyes += lu
                        avance(envoyes)
                    }
                }
            }

            val code = c.responseCode
            val json = lireJson(c, code)
            if (code !in 200..299 || !json.optBoolean("ok")) {
                throw Exception(json.optString("erreur").ifEmpty { "HTTP $code" })
            }
            val empreinteLocale = md.digest().joinToString("") { "%02x".format(it) }
            if (!empreinteLocale.equals(json.optString("sha256"), ignoreCase = true)) {
                throw Exception("empreintes différentes")
            }
            return json.optBoolean("doublon")
        } finally {
            c.disconnect()
        }
    }

    // ---------------------------------------------------------------- Utilitaires

    private fun lireJson(c: HttpURLConnection, code: Int): JSONObject {
        val flux = if (code in 200..299) c.inputStream else c.errorStream
        val texte = flux?.bufferedReader()?.use { it.readText() } ?: ""
        return try { JSONObject(texte) } catch (e: Exception) { JSONObject() }
    }

    private fun enCours(actif: Boolean) {
        boutonDemarrer.isEnabled = !actif
        boutonTester.isEnabled = !actif
        boutonArreter.isEnabled = actif
    }

    private fun statut(texte: String) = runOnUiThread { texteStatut.text = texte }

    private fun progression(fait: Long, total: Long) = runOnUiThread {
        barreProgression.progress = if (total > 0) ((fait * 1000) / total).toInt() else 0
    }

    private fun log(ligne: String) = runOnUiThread {
        journal.addFirst(ligne)            // le plus récent en haut
        while (journal.size > 200) journal.removeLast()
        texteJournal.text = journal.joinToString("\n")
    }

    private fun formater(octets: Long): String = when {
        octets >= 1L shl 30 -> "%.2f Go".format(octets / 1073741824.0)
        octets >= 1L shl 20 -> "%.1f Mo".format(octets / 1048576.0)
        else -> "%d Ko".format(octets / 1024)
    }
}
