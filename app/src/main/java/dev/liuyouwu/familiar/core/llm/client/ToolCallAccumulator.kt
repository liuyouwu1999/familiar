package dev.liuyouwu.familiar.core.llm.client

import com.google.gson.JsonParser
import java.util.LinkedHashMap

data class AccumulatedCall(val id: String, val name: String, val argsJson: String, val argsValid: Boolean)

class ToolCallAccumulator {
    private data class State(var id: String?, var name: String?, val args: StringBuilder)
    private val bySlot = LinkedHashMap<String, State>()

    fun onDelta(index: Int?, id: String?, name: String?, argsFragment: String) {
        val key = index?.toString() ?: id ?: "0"
        val st = bySlot.getOrPut(key) { State(null, null, StringBuilder()) }
        if (id != null) st.id = id
        if (!name.isNullOrEmpty()) st.name = name
        if (argsFragment.isNotEmpty()) st.args.append(argsFragment)
    }

    fun finish(): List<AccumulatedCall> = bySlot.values.map { st ->
        val args = st.args.toString()
        AccumulatedCall(
            id = st.id ?: "", name = st.name ?: "", argsJson = args,
            argsValid = args.isEmpty() || runCatching { JsonParser.parseString(args).isJsonObject }.getOrDefault(false),
        )
    }
}
