package com.example.callvoice

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Contact(val name: String, val number: String)

object Store {
    const val MAX = 10
    private fun p(c: Context) = c.getSharedPreferences("robocall", Context.MODE_PRIVATE)

    fun assistantOn(c: Context) = p(c).getBoolean("assistant", false)
    fun setAssistant(c: Context, v: Boolean) = p(c).edit().putBoolean("assistant", v).apply()

    fun contacts(c: Context): List<Contact> {
        val out = mutableListOf<Contact>()
        try {
            val arr = JSONArray(p(c).getString("contacts", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(Contact(o.getString("name"), o.getString("number")))
            }
        } catch (_: Exception) {}
        return out
    }

    private fun save(c: Context, list: List<Contact>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
        p(c).edit().putString("contacts", arr.toString()).apply()
    }

    /** null یعنی موفق؛ در غیر این صورت متن خطا */
    fun addContact(c: Context, rawName: String, rawNumber: String): String? {
        val name = rawName.trim().lowercase().replace(Regex("\\s+"), " ")
        val number = rawNumber.replace(Regex("[\\s\\-()]"), "")
        if (!Regex("^[a-z ]{2,20}$").matches(name)) return "Name: English letters only (2-20)"
        if (!Regex("^\\+?[0-9]{5,15}$").matches(number)) return "Invalid phone number"
        val list = contacts(c).toMutableList()
        if (list.size >= MAX) return "Maximum $MAX contacts"
        if (list.any { it.name == name }) return "This name already exists"
        list.add(Contact(name, number))
        save(c, list)
        return null
    }

    fun removeContact(c: Context, name: String) {
        save(c, contacts(c).filter { it.name != name })
    }
}
