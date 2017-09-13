package org.neo4j.graphql

import graphql.language.*
import org.neo4j.kernel.internal.Version

fun <T> Iterable<T>.joinNonEmpty(separator: CharSequence = ", ", prefix: CharSequence = "", postfix: CharSequence = "", limit: Int = -1, truncated: CharSequence = "...", transform: ((T) -> CharSequence)? = null): String {
    return if (iterator().hasNext()) joinTo(StringBuilder(), separator, prefix, postfix, limit, truncated, transform).toString() else ""
}

abstract class CypherGenerator {
    companion object {
        val VERSION = Version.getNeo4jVersion()
        val DEFAULT_CYPHER_VERSION = "3.2"

        fun instance(): CypherGenerator {
            return Cypher31Generator()
        }
        fun metaData(name: String) = GraphSchemaScanner.getMetaData(name)

        fun attr(variable: String, field: String) = "`$variable`.`$field`"

        fun isPlural(name: String) = name.endsWith("s")

        fun singular(name: String) = name.substring(0, name.length - 1)

        fun formatValue(value: Value?): String =
                when (value) {
                    is VariableReference -> "{`${value.name}`}"
                // todo turn into parameters  !!
                    is IntValue -> value.value.toString()
                    is FloatValue -> value.value.toString()
                    is BooleanValue -> value.isValue.toString()
                    is StringValue -> "\"${value.value}\""
                    is EnumValue -> "\"${value.name}\""
                    is ObjectValue -> "{" + value.objectFields.map { it.name + ":" + formatValue(it.value) }.joinToString(",") + "}"
                    is ArrayValue -> "[" + value.values.map { formatValue(it) }.joinToString(",") + "]"
                    else -> "" // todo raise exception ?
                }
    }
    abstract fun generateQueryForField(field: Field, fieldDefinition: FieldDefinition? = null, isMutation: Boolean = false): String
}

class Cypher31Generator : CypherGenerator() {
    fun projectMap(field: Field, variable: String, md: MetaData, orderBys: MutableList<Pair<String,Boolean>>): String {
        val selectionSet = field.selectionSet ?: return ""

        return projectSelectionFields(md, variable, selectionSet, orderBys).map{
            val array = md.properties[it.first]?.type?.array ?: false
            // todo fix handling of primitive arrays in graphql-java
            if (array) {
                "`${it.first}` : [x IN ${it.second} | x]"
            } else {
                if (it.second == attr(variable, it.first)) ".`${it.first}`"
                else "`${it.first}` : ${it.second}"
            }
        }.joinNonEmpty(", ","`$variable` {","}")
    }

    fun where(field: Field, variable: String, md: MetaData, orderBys: MutableList<Pair<String,Boolean>>): String {
        val predicates = field.arguments.mapNotNull {
            val name = it.name
            when (name) {
                "orderBy" -> {
                    extractOrderByEnum(it, orderBys)
                    null
                }
                GraphQLSchemaBuilder.ArgumentProperties.NodeId.name -> GraphQLSchemaBuilder.ArgumentProperties.NodeId.argument(variable,field.name, it.value)
                GraphQLSchemaBuilder.ArgumentProperties.NodeIds.name -> GraphQLSchemaBuilder.ArgumentProperties.NodeIds.argument(variable,field.name,it.value)
                "first" -> null
                "offset" -> null
                else -> {
                    if (isPlural(name) && it.value is ArrayValue && md.properties.containsKey(singular(name)))
                        "`${variable}`.`${singular(name)}` IN ${formatValue(it.value)}"
                    else
                        "`${variable}`.`$name` = ${formatValue(it.value)}"
                }
            // todo directives for more complex filtering
        }}.joinToString("\nAND ")
        return if (predicates.isBlank()) "" else "WHERE " + predicates
    }

    private fun extractOrderByEnum(argument: Argument, orderBys: MutableList<Pair<String, Boolean>>) {
        fun extractSortFields(arg: EnumValue) : Unit {
            val name = arg.name
            if (name.endsWith("_desc")) {
                orderBys.add(Pair(name.substring(0,name.lastIndexOf("_")), false))
            }
            if (name.endsWith("_asc")) {
                orderBys.add(Pair(name.substring(0,name.lastIndexOf("_")), true))
            }
        }

        val value = argument.value
        if (value is EnumValue) {
            extractSortFields(value)
        }
        if (value is ArrayValue) {
            value.values.filterIsInstance<EnumValue>().forEach{extractSortFields(it)}
        }
    }

    fun projectSelectionFields(md: MetaData, variable: String, selectionSet: SelectionSet, orderBys: MutableList<Pair<String, Boolean>>): List<Pair<String, String>> {
        return listOf(Pair("_labels", "labels(`$variable`)")) +
                projectFragments(md, variable, selectionSet.selections, orderBys) +
                selectionSet.selections.filterIsInstance<Field>().mapNotNull { projectField(it, md, variable, orderBys) }
    }

    fun projectFragments(md: MetaData, variable: String, selections: MutableList<Selection>, orderBys: MutableList<Pair<String, Boolean>>): List<Pair<String, String>> {
        return selections.filterIsInstance<InlineFragment>().flatMap {
            val fragmentTypeName = it.typeCondition.name
            val fragmentMetaData = GraphSchemaScanner.getMetaData(fragmentTypeName)!!
            if (fragmentMetaData.labels.contains(md.type)) {
                // these are the nested fields of the fragment
                // it could be that we have to adapt the variable name too, and perhaps add some kind of rename
                it.selectionSet.selections.filterIsInstance<Field>().map { projectField(it, fragmentMetaData, variable, orderBys) }.filterNotNull()
            } else {
                emptyList<Pair<String, String>>()
            }
        }
    }


    private fun projectField(f: Field, md: MetaData, variable: String, orderBys: MutableList<Pair<String, Boolean>>): Pair<String, String>? {
        val field = f.name

        val cypherStatement = md.cypherFor(field)
        val relationship = md.relationshipFor(field) // todo correct medatadata of

        val expectMultipleValues = md.properties[field]?.type?.array ?: true

        return if (!cypherStatement.isNullOrEmpty()) {

            val arguments = f.arguments.associate { it.name to it.value.extract() }
                    .mapValues { if (it.value is String) "\"${it.value}\"" else it.value.toString() }

            val params = (mapOf("this" to "`$variable`") + arguments).entries
                    .joinToString(",", "{", "}") { "`${it.key}`:${it.value}" }

            val prefix  = if (!cypherStatement!!.contains(Regex("this\\s*\\}?\\s+AS\\s+",RegexOption.IGNORE_CASE))) "WITH {this} AS this " else ""
            val cypherFragment = "graphql.run('${prefix}${cypherStatement}', $params, $expectMultipleValues)"

            if (relationship != null) {
                val (patternComp, _) = formatCypherDirectivePatternComprehension(md, cypherFragment, f)
                Pair(field, if (relationship.multi) patternComp else "head(${patternComp})")
            } else {
                Pair(field, cypherFragment) // TODO escape cypher statement quotes
            }
        } else {
            if (relationship == null) {
                if (GraphQLSchemaBuilder.ArgumentProperties.NodeId.matches(field)) GraphQLSchemaBuilder.ArgumentProperties.NodeId.render(variable)
                else Pair(field, attr(variable, field))
            } else {
                if (f.selectionSet == null) null // todo
                else {
                    val (patternComp, _) = formatPatternComprehension(md, variable, f, orderBys) // metaData(info.label)
                    Pair(field, if (relationship.multi) patternComp else "head(${patternComp})")
                }
            }
        }
    }

    fun nestedPatterns(metaData: MetaData, variable: String, selectionSet: SelectionSet, orderBys: MutableList<Pair<String,Boolean>>): String {
        return projectSelectionFields(metaData, variable, selectionSet, orderBys).map{ pair ->
            val (fieldName, projection) = pair
            "$projection AS `$fieldName`"
        }.joinToString(",\n","RETURN ")
    }

    fun formatCypherDirectivePatternComprehension(md: MetaData, cypherFragment: String, field: Field): Pair<String,String> {
        val fieldName = field.name
        val info = md.relationshipFor(fieldName) ?: return Pair("","")
        val fieldMetaData = GraphSchemaScanner.getMetaData(info.label)!!

        val pattern = "x IN $cypherFragment"

        val projection = projectMap(field, "x", fieldMetaData, mutableListOf<Pair<String, Boolean>>())
        val result = "[ $pattern | $projection ]"
        val skipLimit = skipLimit(field)
        return Pair(result + subscript(skipLimit), "x")
    }

    private fun subscriptInt(skipLimit: Pair<Number?, Number?>): String {
        if (skipLimit.first == null && skipLimit.second == null) return ""

        val skip = skipLimit.first?.toInt() ?: 0
        val limit = if (skipLimit.second == null) -1 else skip + (skipLimit.second?.toInt() ?: 0)
        return "[$skip..$limit]"
    }
    private fun subscript(skipLimit: Pair<String?, String?>): String {
        if (skipLimit.first == null && skipLimit.second == null) return ""

        val skip = skipLimit.first ?: "0"
        val limit = if (skipLimit.second == null) "-1" else skip + "+" + (skipLimit.second ?: "0")
        return "[$skip..$limit]"
    }

    fun formatPatternComprehension(md: MetaData, variable: String, field: Field, orderBysIgnore: MutableList<Pair<String,Boolean>>): Pair<String,String> {
        val fieldName = field.name
        val info = md.relationshipFor(fieldName) ?: return Pair("","")
        val fieldVariable = variable + "_" + fieldName

        val arrowLeft = if (!info.out) "<" else ""
        val arrowRight = if (info.out) ">" else ""

        val fieldMetaData = GraphSchemaScanner.getMetaData(info.label)!!

        val pattern = "(`$variable`)$arrowLeft-[:`${info.type}`]-$arrowRight(`$fieldVariable`:`${info.label}`)"
        val orderBys2 = mutableListOf<Pair<String,Boolean>>()
        val where = where(field, fieldVariable, fieldMetaData, orderBys2)
        val projection = projectMap(field, fieldVariable, fieldMetaData, orderBysIgnore) // [x IN graph.run ... | x {.name, .age } ] as recommendedMovie if it's a relationship/entity Person / Movie
        var result = "[ $pattern $where | $projection]"
        // todo parameters, use subscripts instead
        val skipLimit = skipLimit(field)
        if (orderBys2.isNotEmpty()) {
            val orderByParams = orderBys2.map { "'${if (it.second) "^" else ""}${it.first}'" }.joinToString(",", "[", "]")
            result = "graphql.sortColl($result,$orderByParams)"
        }
        return Pair(result + subscript(skipLimit),fieldVariable)
    }

    override fun generateQueryForField(field: Field, fieldDefinition: FieldDefinition?, isMutation: Boolean): String {
        val name = field.name
        val typeName = fieldDefinition?.type?.inner() ?: "no field definition"
        val md = metaData(name) ?: metaData(typeName) ?: throw IllegalArgumentException("Cannot resolve as type $name or $typeName")
        val variable = md.type
        val orderBys = mutableListOf<Pair<String,Boolean>>()
        val procedure = if (isMutation) "updateForNodes" else "queryForNodes"
        val isDynamic = fieldDefinition?.cypher() != null
        val query = fieldDefinition?.cypher()?.
                let {
                    val passedInParams = field.arguments.map { "`${it.name}` : {`${it.name}`}" }.joinToString(",", "{", "}")
                    """CALL graphql.$procedure("${it.first}",${passedInParams}) YIELD node AS `$variable`"""
                }
                ?: "MATCH (`$variable`:`$name`)"

        val projectFields = projectSelectionFields(md, variable, field.selectionSet, orderBys)
        val resultProjection = projectFields.map { pair ->
            val (fieldName, projection) = pair
            // todo fix handling of primitive arrays in graphql-java
            val type = md.properties[fieldName]?.type
            if (type?.array == true && md.cypherFor(fieldName) == null) {
                "[x IN $projection |x] AS `$fieldName`"
            } else {
                "$projection AS `$fieldName`"
            }
        }.joinToString(",\n","RETURN ")

        val resultFieldNames = projectFields.map { it.first }.toSet()
        val where = if (isDynamic) "" else where(field, variable, md, orderBys)
        val parts = listOf(
                query,
                where,
                resultProjection,
                // todo check if result is in returned projections
                orderBys.map { (if (!resultFieldNames.contains(it.first))  "`$variable`." else "") + "`${it.first}` ${if (it.second) "asc" else "desc"}" }.joinNonEmpty(",", "ORDER BY ")
        ) +  skipLimitStatements(skipLimit(field))

        val statement = parts.filter { !it.isNullOrEmpty() }.joinToString("\n")
        println(statement)
        return statement
    }

    private fun cypherDirective(field: Field): Directive? =
            field.directives.filter { it.name == "cypher" }.firstOrNull()

    private fun skipLimitStatements(skipLimit: Pair<String?, String?>) =
            listOf<String?>( skipLimit.first?.let { "SKIP $it" },skipLimit.second?.let { "LIMIT $it" })

    private fun skipLimitInt(field: Field): Pair<Number?,Number?> = Pair(
            intValue(argumentByName(field, "offset")),
            intValue(argumentByName(field, "first")))

    private fun skipLimit(field: Field): Pair<String?,String?> = Pair(
            argumentValueOrParam(field, "offset"), argumentValueOrParam(field, "first"))

    private fun argumentByName(field: Field, name: String) = field.arguments.firstOrNull { it.name == name }

    private fun argumentValueOrParam(field: Field, name: String)
            = field.arguments
            .filter { it.name == name }
            .map { it.value }
            // todo variables seem to be automatically resolved to field name variables
            .map { if (it is VariableReference) "{${name}}" else valueAsString(it) }
            .firstOrNull()

    private fun valueAsString(it: Value?): String? = when (it) {
        is IntValue -> it.value.toString()
        is FloatValue -> it.value.toString()
        is StringValue -> "'" + it.value + "'"
        is EnumValue -> "'" + it.name + "'"
        is BooleanValue -> it.isValue.toString()
        is ArrayValue -> it.values.map { valueAsString(it) }.joinToString(",","[","]")
        is ObjectValue -> it.objectFields.map { "`${it.name}`:${valueAsString(it.value)}" }.joinToString(",","{","}")
        is VariableReference -> "{${it.name}}"
        else -> null
    }

    private fun intValue(it: Argument?) : Number? {
        val value = it?.value
        return if (value is IntValue) value.value
        else null
    }
}
