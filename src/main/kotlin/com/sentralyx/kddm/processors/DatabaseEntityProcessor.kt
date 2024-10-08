package com.sentralyx.kddm.processors

import com.sentralyx.kddm.annotations.*
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.jvm.throws
import java.sql.SQLException
import java.util.*
import javax.annotation.processing.*
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement

@SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_8)
@SupportedAnnotationTypes("com.sentralyx.kddm.annotations.DatabaseEntity", "com.sentralyx.kddm.annotations.Query")
class DatabaseEntityProcessor : AbstractProcessor() {

    private val version: String = loadVersion()

    private fun loadVersion(): String {
        val properties = Properties()
        this.javaClass.classLoader.getResourceAsStream("version.properties")?.use { inputStream ->
            properties.load(inputStream)
        }
        return properties.getProperty("version", "unknown")
    }

    /**
     * Processes the annotations by generating database models for each annotated class.
     *
     * @param annotations A set of annotations present in the current round.
     * @param roundEnv The environment for the current processing round.
     * @return True if the annotations were processed successfully.
     */
    override fun process(annotations: MutableSet<out TypeElement>, roundEnv: RoundEnvironment): Boolean {
        val elementsAnnotatedWith = roundEnv.getElementsAnnotatedWith(DatabaseEntity::class.java)
        for (element in elementsAnnotatedWith) {
            if (element.kind.isClass) {
                generateDatabaseModel(element)
            }
        }

        val methodsAnnotatedWithQuery = roundEnv.getElementsAnnotatedWith(Query::class.java)
        for (method in methodsAnnotatedWithQuery) {
            if (method.kind == ElementKind.METHOD) {
                generateQueryMethod(method)
            }
        }

        return true
    }

    /**
     * Generates an extension function for the annotated class, which provides access
     * to the corresponding database model class.
     *
     * This extension function allows the annotated class to directly access the
     * generated database model without explicitly creating an instance. It adds a
     * `model()` function to the class, which returns an instance of the generated
     * `${className}DatabaseModel`.
     *
     * For example, if `User` is annotated with `@DatabaseEntity`, this function will
     * generate the following extension function:
     *
     * ```
     * fun User.model(): UserDatabaseModel {
     *     return UserDatabaseModel()
     * }
     * ```
     *
     * This method can then be used to interact with the database model for the class.
     *
     * @param className The name of the annotated class.
     * @param packageName The package name where the annotated class is located.
     * @return A [FunSpec] representing the extension function for the database model.
     */
    private fun generateModelExtensionFunction(className: String, packageName: String): FunSpec {
        return FunSpec.builder("${className.lowercase()}Model")
            .receiver(ClassName(packageName, className))
            .returns(ClassName(packageName, "${className}DatabaseModel"))
            .addStatement("return ${className}DatabaseModel(this)")
            .build()
    }

    /**
     * Generates a database model for the specified entity class.
     *
     * This method creates a Kotlin data class representing the database model,
     * including insert, select, update, and delete functions.
     *
     * @param element The element representing the database entity class.
     */
    @OptIn(DelicateKotlinPoetApi::class)
    private fun generateDatabaseModel(element: Element) {
        val className = element.simpleName.toString()
        val packageName = processingEnv.elementUtils.getPackageOf(element).toString()

        val entityAnnotation = element.getAnnotation(DatabaseEntity::class.java)
        val tableName = entityAnnotation.tableName.ifEmpty { className.lowercase() }
        val fields = element.enclosedElements.filter { it.kind == ElementKind.FIELD }

        val fieldSpecs = fields.map { field ->
            val fieldName = field.simpleName.toString()
            val fieldType = field.asType().asTypeName()

            val columnType = field.getAnnotation(ColumnType::class.java)
                ?: throw IllegalArgumentException("Field $fieldName must have a ColumnType annotation")

            val sqlType = columnType.type.name
            val size = columnType.size

            val returnType = if (columnType.type.isSizeable) {
                if (size > columnType.type.maxSize)
                    throw IllegalArgumentException("Field $fieldName value size must be ${columnType.type.maxSize} or lower. Current size ($size)")
                "$sqlType(${size})"
            } else {
                sqlType
            }

            PropertySpec.builder(fieldName, fieldType)
                .initializer(fieldName)
                .addModifiers(KModifier.PRIVATE)
                .build() to returnType
        }

        val primaryKey = fields.map { field ->
            val fieldName = field.simpleName.toString()
            val hasPrimaryKey = field.getAnnotation(PrimaryKey::class.java) != null

            if (hasPrimaryKey)
                fieldName
            else
                ""
        }

        val primaryKeyFieldSpec = fieldSpecs.firstOrNull { (propertySpec, _) ->
            val fieldElement = fields.find { it.simpleName.toString() == propertySpec.name }
            fieldElement?.getAnnotation(PrimaryKey::class.java) != null
        }?.first ?: throw IllegalArgumentException("No field annotated with @PrimaryKey found in $className")

        val classBuilder = TypeSpec.classBuilder("${className}DatabaseModel")
            .primaryConstructor(
                FunSpec.constructorBuilder()
                    .addParameter("currentUser", ClassName(packageName, className))
                    .build()
            )
            .addProperty(
                PropertySpec.builder("currentUser", ClassName(packageName, className))
                    .initializer("currentUser")
                    .mutable(true)
                    .build()
            )
            .addFunction(generateInsertFunction(packageName, className, tableName, fieldSpecs, primaryKey.firstOrNull()))
            .addFunction(generateSelectFunction(packageName, className, tableName, primaryKeyFieldSpec, fieldSpecs))
            .addFunction(generateSelectSelfFunction(packageName, className, tableName, primaryKeyFieldSpec, fieldSpecs))
            .addFunction(generateUpdateFunction(packageName, className, tableName, fieldSpecs, primaryKeyFieldSpec))
            .addFunction(generateDeleteFunction(className, tableName, primaryKeyFieldSpec))
            .addFunction(generateDeleteUserFunction(className, tableName, primaryKeyFieldSpec))
            .addFunction(generateCreateTableFunction(tableName = tableName, element = element))

        val kotlinFile = FileSpec.builder(packageName, "${className}DatabaseModel")
            .addImport("com.sentralyx.kddm.connector.MySQLConnector", "getConnection")
            .addType(classBuilder.build())
            .addFileComment("""
                /**
                 * This class is under MIT license. Please read before usage.
                 * The class was auto-generated by DynamicDatabaseManagement library.
                 * Do not edit this class!
                 *
                 * Version: ${this.version}
                 *
                 * Generates a database model for the specified entity class.
                 *
                 * This method creates a Kotlin data class representing the database model,
                 * including insert, select, update, and delete functions.
                 *
                 * @param element The element representing the database entity class.
                 */
            """.trimIndent())
            .addFunction(generateModelExtensionFunction(className, packageName))

        kotlinFile.build().writeTo(processingEnv.filer)
    }

    /**
     * Generates an insert function for the database model.
     *
     * @param className The name of the class to insert into the database.
     * @param tableName The name of the database table.
     * @param fields The fields of the database model.
     * @return A function specification for inserting data into the database.
     */
    private fun generateInsertFunction(
        packageName: String,
        className: String,
        tableName: String,
        fields: List<Pair<PropertySpec, String>>,
        primaryKeyName: String?
    ): FunSpec {
        val filteredFields = fields.filterNot { (property, _) ->
            property.name == primaryKeyName
        }

        val insertQuery = "INSERT INTO $tableName (${filteredFields.joinToString { it.first.name }}) VALUES (${filteredFields.joinToString { "?" }})"

        val code = buildCodeBlock {
            addStatement("getConnection().use { connection ->")
            addStatement("    val statement = connection.prepareStatement(%P)", insertQuery)

            filteredFields.forEachIndexed { index, (property, _) ->
                addStatement("    statement.setObject(${index + 1}, obj.${property.name})")
            }

            addStatement("    statement.executeUpdate()")
            addStatement("}")
        }

        return FunSpec.builder("insert${className}")
            .addParameter("obj", ClassName(packageName, className))
            .addCode(code)
            .throws(SQLException::class)
            .build()
    }

    /**
     * Generates a select function for the database model.
     *
     * @param className The name of the class to select from the database.
     * @param tableName The name of the database table.
     * @param primaryKeyField The primary key field of the database model.
     * @return A function specification for selecting data from the database.
     */
    private fun generateSelectFunction(
        packageName: String,
        className: String,
        tableName: String,
        primaryKeyField: PropertySpec,
        fields: List<Pair<PropertySpec, String>>
    ): FunSpec {
        val selectQuery =
            "SELECT ${fields.joinToString { it.first.name }} FROM $tableName WHERE ${primaryKeyField.name} = ?"

        val code = buildCodeBlock {
            addStatement("getConnection().use { connection ->")
            addStatement("    val preparedStatement = connection.prepareStatement(%P)", selectQuery)
            addStatement("    preparedStatement.setObject(1, id)")
            addStatement("    val resultSet = preparedStatement.executeQuery()")
            beginControlFlow("    if (resultSet.next())")
            val constructorArgs = fields.joinToString(", ") {
                val getter = when (it.first.type) {
                    INT -> "getInt"
                    STRING -> "getString"
                    BOOLEAN -> "getBoolean"
                    FLOAT -> "getFloat"
                    LONG -> "getLong"
                    DOUBLE -> "getDouble"
                    SHORT -> "getShort"
                    else -> when (MySQLType.valueOf(it.second.split("(")[0])) {
                        MySQLType.INT -> "getInt"
                        MySQLType.VARCHAR -> "getString"
                        MySQLType.BOOL, MySQLType.TINYINT -> "getBoolean"
                        MySQLType.FLOAT -> "getFloat"
                        MySQLType.BIGINT -> "getLong"
                        MySQLType.TIMESTAMP -> "getTimestamp"
                        MySQLType.DECIMAL -> "getDouble"
                        MySQLType.JSON -> "getString"

                        // TODO: Add more available types.

                        else -> "getObject"
                    }
                }

                "resultSet.${getter}(\"${it.first.name}\")"
            }
            addStatement("return %T($constructorArgs)", ClassName(packageName, className))
            endControlFlow()

            addStatement("    return null")
            addStatement("}")
        }

        return FunSpec.builder("select${className}ById")
            .addParameter("id", Any::class)
            .returns(ClassName(packageName, className).copy(nullable = true))
            .addCode(code)
            .throws(SQLException::class)
            .build()
    }

    private fun generateSelectSelfFunction(
        packageName: String,
        className: String,
        tableName: String,
        primaryKeyField: PropertySpec,
        fields: List<Pair<PropertySpec, String>>
    ): FunSpec {
        val selectQuery =
            "SELECT ${fields.joinToString { it.first.name }} FROM $tableName WHERE ${primaryKeyField.name} = ?"

        val code = buildCodeBlock {
            addStatement("getConnection().use { connection ->")
            addStatement("    val preparedStatement = connection.prepareStatement(%P)", selectQuery)
            addStatement("    preparedStatement.setInt(1, this.currentUser.id)")
            addStatement("    val resultSet = preparedStatement.executeQuery()")
            beginControlFlow("    if (resultSet.next())")
            val constructorArgs = fields.joinToString(", ") {
                val getter = when (it.first.type) {
                    INT -> "getInt"
                    STRING -> "getString"
                    BOOLEAN -> "getBoolean"
                    FLOAT -> "getFloat"
                    LONG -> "getLong"
                    DOUBLE -> "getDouble"
                    SHORT -> "getShort"
                    else -> when (MySQLType.valueOf(it.second.split("(")[0])) {
                        MySQLType.INT -> "getInt"
                        MySQLType.VARCHAR -> "getString"
                        MySQLType.BOOL, MySQLType.TINYINT -> "getBoolean"
                        MySQLType.FLOAT -> "getFloat"
                        MySQLType.BIGINT -> "getLong"
                        MySQLType.TIMESTAMP -> "getTimestamp"
                        MySQLType.DECIMAL -> "getDouble"
                        MySQLType.JSON -> "getString"

                        // TODO: Add more available types.

                        else -> "getObject"
                    }
                }

                "resultSet.${getter}(\"${it.first.name}\")"
            }
            addStatement("return %T($constructorArgs)", ClassName(packageName, className))
            endControlFlow()

            addStatement("    return null")
            addStatement("}")
        }

        return FunSpec.builder("select${className}")
            .returns(ClassName(packageName, className).copy(nullable = true))
            .addCode(code)
            .throws(SQLException::class)
            .build()
    }

    /**
     * Generates an update function for the database model.
     *
     * @param className The name of the class to update in the database.
     * @param tableName The name of the database table.
     * @param fields The fields of the database model.
     * @param primaryKeyField The primary key field of the database model.
     * @return A function specification for updating data in the database.
     */
    private fun generateUpdateFunction(
        packageName: String,
        className: String,
        tableName: String,
        fields: List<Pair<PropertySpec, String>>,
        primaryKeyField: PropertySpec
    ): FunSpec {
        val setClause = fields.joinToString { "${it.first.name} = ?" }
        val updateQuery = "UPDATE $tableName SET $setClause WHERE ${primaryKeyField.name} = ?"

        val code = buildCodeBlock {
            addStatement("getConnection().use { connection ->")
            addStatement("    val preparedStatement = connection.prepareStatement(%P)", updateQuery)

            fields.forEachIndexed { index, (property, _) ->
                addStatement("    preparedStatement.setObject(${index + 1}, obj.${property.name})")
            }

            addStatement("    preparedStatement.setObject(${fields.size + 1}, obj.${primaryKeyField.name})")
            addStatement("    preparedStatement.executeUpdate()")
            addStatement("}")
        }

        return FunSpec.builder("update${className}")
            .addParameter("obj", ClassName(packageName, className))
            .addCode(code)
            .throws(SQLException::class)
            .build()
    }

    /**
     * Generates a delete function for the database model.
     *
     * @param className The name of the class to delete from the database.
     * @param tableName The name of the database table.
     * @param primaryKeyField The primary key field of the database model.
     * @return A function specification for deleting data from the database.
     */
    private fun generateDeleteFunction(
        className: String,
        tableName: String,
        primaryKeyField: PropertySpec
    ): FunSpec {
        val deleteQuery = "DELETE FROM $tableName WHERE ${primaryKeyField.name} = ?"

        val code = buildCodeBlock {
            addStatement("getConnection().use { connection ->")
            addStatement("    connection.prepareStatement(%P).use { preparedStatement ->", deleteQuery)
            addStatement("        preparedStatement.setObject(1, id)")
            addStatement("        val result = preparedStatement.executeUpdate()")
            addStatement("        block(result)")
            addStatement("    }")
            addStatement("}")
        }

        val resultSetType = ClassName("java.sql", "ResultSet")
        val lambdaType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.builder("resultSet", resultSetType).build()),
            returnType = UNIT
        )

        return FunSpec.builder("delete${className}ById")
            .addParameter("id", Any::class)
            .addParameter("block", lambdaType)
            .addCode(code)
            .throws(SQLException::class)
            .build()
    }

    private fun generateDeleteUserFunction(
        className: String,
        tableName: String,
        primaryKeyField: PropertySpec
    ): FunSpec {
        val deleteQuery = "DELETE FROM $tableName WHERE ${primaryKeyField.name} = ?"

        val code = buildCodeBlock {
            addStatement("getConnection().use { connection ->")
            addStatement("    connection.prepareStatement(%P).use { preparedStatement ->", deleteQuery)
            addStatement("        preparedStatement.setInt(1, this.currentUser.id)")
            addStatement("        val result = preparedStatement.executeUpdate()")
            addStatement("        block(result)")
            addStatement("    }")
            addStatement("}")
        }

        val resultSetType = ClassName("java.sql", "ResultSet")
        val lambdaType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.builder("resultSet", resultSetType).build()),
            returnType = UNIT
        )

        return FunSpec.builder("delete${className}")
            .addCode(code)
            .addParameter("block", lambdaType)
            .throws(SQLException::class)
            .build()
    }

    @OptIn(DelicateKotlinPoetApi::class)
    private fun generateQueryMethod(method: Element) {
        val queryAnnotation = method.getAnnotation(Query::class.java)
        val query = queryAnnotation.value

        val methodName = method.simpleName.toString()
        val className = (method.enclosingElement as TypeElement).simpleName.toString()
        val packageName = processingEnv.elementUtils.getPackageOf(method).toString()

        val parameters = method as? ExecutableElement ?: throw IllegalArgumentException("Element is not a method")
        val parameterSpec = parameters.parameters.map { param ->
            ParameterSpec.builder(param.simpleName.toString(), param.asType().asTypeName()).build()
        }

        val codeBlock = buildCodeBlock {
            addStatement("val connection = getConnection()")
            addStatement("val preparedStatement = connection.prepareStatement(%P)", query)

            parameters.parameters.forEachIndexed { index, param ->
                addStatement("preparedStatement.setObject(${index + 1}, ${param.simpleName})")
            }

            addStatement("val resultSet = preparedStatement.executeQuery()")
            addStatement("block(resultSet)")
        }

        val resultSetType = ClassName("java.sql", "ResultSet")
        val lambdaType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.builder("resultSet", resultSetType).build()),
            returnType = UNIT
        )

        val funSpec = FunSpec.builder(methodName)
            .addParameters(parameterSpec)
            .addModifiers(KModifier.PUBLIC)
            .addParameter("block", lambdaType)
            .returns(Unit::class)
            .addCode(codeBlock)
            .build()

        val fileSpec = FileSpec.builder(packageName, className)
            .addFunction(funSpec)
            .addImport("com.sentralyx.kddm.connector.MySQLConnector", "getConnection")
            .build()

        fileSpec.writeTo(processingEnv.filer)
    }


    // Add the following method in the DatabaseEntityProcessor class

    /**
     * Generates a create table function for the database model.
     *
     * @param className The name of the class to create the table for.
     * @param tableName The name of the database table.
     * @param fields The fields of the database model.
     * @return A function specification for creating the database table.
     */
    private fun generateCreateTableFunction(
        tableName: String,
        element: Element
    ): FunSpec {
        val fields = element.enclosedElements.filter { it.kind == ElementKind.FIELD }
        val fieldDefinitions = fields.map { field ->
            val fieldName = field.simpleName.toString()
            val sqlType = field.getAnnotation(ColumnType::class.java)
            val isPrimaryKey = field.getAnnotation(PrimaryKey::class.java) != null
            val isUnique = field.getAnnotation(Unique::class.java) != null
            val isNonNull = field.getAnnotation(NotNull::class.java) != null

            val baseSqlType = sqlType?.type?.name ?: throw IllegalArgumentException("Field $fieldName must have a SQLType annotation")
            val size = sqlType.size.takeIf { it > 0 }

            val constraints = mutableListOf<String>()
            if (isPrimaryKey) constraints.add("PRIMARY KEY")
            if (isUnique) constraints.add("UNIQUE")
            if (isNonNull) constraints.add("NOT NULL")

            val defaultValue = when {
                field.getAnnotation(DefaultIntValue::class.java) != null -> {
                    "DEFAULT ${field.getAnnotation(DefaultIntValue::class.java).value}"
                }
                field.getAnnotation(DefaultStringValue::class.java) != null -> {
                    "DEFAULT '${field.getAnnotation(DefaultStringValue::class.java).value}'"
                }
                field.getAnnotation(DefaultFloatValue::class.java) != null -> {
                    "DEFAULT ${field.getAnnotation(DefaultFloatValue::class.java).value}"
                }
                field.getAnnotation(DefaultDoubleValue::class.java) != null -> {
                    "DEFAULT ${field.getAnnotation(DefaultDoubleValue::class.java).value}"
                }
                field.getAnnotation(DefaultLongValue::class.java) != null -> {
                    "DEFAULT ${field.getAnnotation(DefaultLongValue::class.java).value}"
                }
                field.getAnnotation(DefaultShortValue::class.java) != null -> {
                    "DEFAULT ${field.getAnnotation(DefaultShortValue::class.java).value}"
                }
                field.getAnnotation(DefaultBoolValue::class.java) != null -> {
                    "DEFAULT ${field.getAnnotation(DefaultBoolValue::class.java).value}"
                }
                else -> ""
            }

            "`$fieldName` $baseSqlType${size?.let { "($size)" } ?: ""} ${constraints.joinToString(" ")} $defaultValue"
        }

        val foreignKeyConstraints = fields.mapNotNull { field ->
            val fieldName = field.simpleName.toString()
            val foreignKey = field.getAnnotation(ForeignKey::class.java) ?: return@mapNotNull null

            val foreignKeyUpdates = mutableListOf<String>()
            if (foreignKey.onDelete != ForeignKeyType.NO_ACTION)
                foreignKeyUpdates.add("ON DELETE ${foreignKey.onDelete.name.replace("_", " ").uppercase()}")
            if (foreignKey.onUpdate != ForeignKeyType.NO_ACTION)
                foreignKeyUpdates.add("ON UPDATE ${foreignKey.onUpdate.name.replace("_", " ").uppercase()}")

            "FOREIGN KEY (`$fieldName`) REFERENCES `${foreignKey.targetTable}` (`${foreignKey.targetName}`) ${foreignKeyUpdates.joinToString(" ")}"
        }

        val combinedSqlParts = fieldDefinitions + foreignKeyConstraints
        val combinedSql = combinedSqlParts.joinToString(", ")
        val sql = "CREATE TABLE `$tableName` ($combinedSql)"

        val lambdaType = LambdaTypeName.get(
            parameters = listOf(ParameterSpec.builder("result", Boolean::class.java).build()),
            returnType = UNIT
        )

        val code = buildCodeBlock {
            addStatement("getConnection().use { connection ->")
                addStatement("  val meta = connection.metaData")
                addStatement("  val resultSet = meta.getTables(null, null, %S, null)", tableName)
                addStatement("  val isNext = resultSet.next()")
                beginControlFlow("if (!isNext)")
                    addStatement("  connection.createStatement().use { statement ->")
                    addStatement("    statement.executeUpdate(%S)", sql)
                    addStatement("  }")
                endControlFlow()
                addStatement("  block(isNext)")
            addStatement("}")
        }

        return FunSpec.builder("createTable")
            .addParameter("block", lambdaType)
            .addCode(code)
            .throws(SQLException::class)
            .build()
    }
}