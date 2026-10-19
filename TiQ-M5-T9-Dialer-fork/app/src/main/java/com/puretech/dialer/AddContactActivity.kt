package com.puretech.dialer

import android.app.Activity
import android.content.ContentProviderOperation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.puretech.dialer.databinding.ActivityAddContactBinding

/**
 * Standalone "new contact" screen, replacing the call to the system contact editor at every
 * add-to-contacts spot in the app. The system editor's name field only accepts digits on this
 * hardware keypad (no on-screen IME); this screen types the name with [T9Buffer] instead, same
 * as the M5S launcher's own text-entry screen.
 *
 * The number itself is pre-filled and not editable here -- it already went through
 * Dialer.normalize wherever this is launched from. The new raw contact is left with no explicit
 * account (ACCOUNT_TYPE/ACCOUNT_NAME both null): on current Android this lands in whichever
 * account the user picked as "Default account for new contacts" in Settings > Accounts, the same
 * as any other app that doesn't force a specific account, so it syncs like a normal contact
 * whenever such a default is set.
 */
class AddContactActivity : Activity() {
    private lateinit var binding: ActivityAddContactBinding
    private val name = T9Buffer()
    private var onPhone = false // true while the user is editing the number instead of the name

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddContactBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.back.setOnClickListener { finish() }
        binding.numberValue.text = intent.getStringExtra(EXTRA_NUMBER).orEmpty()
        binding.saveBtn.setOnClickListener { save() }
        binding.cancelBtn.setOnClickListener { finish() }
        render()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (name.onKeyDown(event.keyCode)) { render(); return true }
            if (event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) {
                save(); return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun render() {
        binding.nameValue.text = if (name.text.isEmpty()) getString(R.string.add_contact_name_hint) else name.text.toString()
        binding.modeHint.text = name.modeLabel() + "  (" + getString(R.string.add_contact_mode_hint) + ")"
    }

    private fun save() {
        val number = binding.numberValue.text.toString()
        val displayName = name.text.toString().trim()
        if (displayName.isEmpty()) {
            Toast.makeText(this, R.string.add_contact_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val ops = ArrayList<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).build()
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                    .build()
            )
            if (number.isNotBlank()) {
                ops.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build()
                )
            }
            contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
            Toast.makeText(this, R.string.add_contact_saved, Toast.LENGTH_SHORT).show()
            finish()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.add_contact_failed, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val EXTRA_NUMBER = "number"
        fun start(context: Context, number: String) {
            context.startActivity(
                Intent(context, AddContactActivity::class.java)
                    .putExtra(EXTRA_NUMBER, number)
                    .addFlags(if (context !is Activity) Intent.FLAG_ACTIVITY_NEW_TASK else 0)
            )
        }
    }
}
