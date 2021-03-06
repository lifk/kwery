The mapper module builds on core to provide typical DAO (Data Access Object) functionality.

#### Mapping 

As Kwery believes your domain model shouldn't be tainted by mapping annotations,
it uses a `Table` object to define the mapping between rows and classes.

Given the following SQL table:
 
```SQL
create sequence actor_seq;
create table actor (
    actor_id    integer generated by default as sequence actor_seq primary key,
    first_name  character varying(255) not null,
    last_name   character varying(255) not null,
    last_update timestamp              not null
);
```

The following will map the table to and from the defined `Name` and `Actor` classes.

Note that the mapping doesn't have to be flat - here we're combining the first and
last names into their own class.

```kotlin
// We'll map to standard immutable classes
class Name(val firstName: String, val lastName: String)
class Actor(val id: Int, val name: Name, val lastUpdate: LocalDateTime)

// Table configuration defines defaults, converters and naming conventions
// In this case, it includes converters for LocalDateTime <-> java.sql.Timestamp
val tableConfig = TableConfiguration(defaults, converters, camelToLowerUnderscore)

// A table object defines the mapping between columns and models
// Conversions default to those defined in the configuration but may be overridden
object actorTable : Table<Actor, Int>("actor", tableConfig), VersionedWithTimestamp {
    val ActorId    by col(Actor::id, id = true)
    val FirstName  by col(Name::firstName, Actor::name)
    val LastName   by col(Name::lastName, Actor::name)
    val LastUpdate by col(Actor::lastUpdate, version = true)

    override fun idColumns(id: Int) = setOf(ActorId of id)

    override fun create(value: Value<Actor>) = Actor(value of ActorId,
            Name(value of FirstName, value of LastName), value of LastUpdate)
}
```

Each column in a table should have a corresponding `val` defined in the table object.

The name of the `val` should match the name of the column after the naming convention
is applied.

So in the example above, `val ActorId` matches the `actor_id` column as we are
using the `camelToLowerUnderscore` naming convention.

`idColumns` must be overridden to define how to apply the primary key to an object.

The `create` function essentially allows row to be converted into an object
in a type safe manner.

A row does not have to be mapped to a flat structure. In the example above,
the first and last names are combined into a `Name` class.

To achieve this you must:

1. Use the overloaded variant of `col` that accepts a `property` (`Name::firstname`) and
 a `path` (`{ it.name }`). The `path` arguments defines how fetch the `Name` object
 given an `Actor` object in example above.

2. Ensure your `create` function creates the appropriate nested structure (in this
 case it constructs a `Name` object from the `FirstName` and `LastName` values).

Finally, a `Column` object can be added directly if explicit control over mapping
is required.

#### Data Access Objects (DAOs)

Continuing the example from above a single line let's us use the `Table` definition to
create a DAO will all the standard CRUD operations.

```kotlin
class ActorDao(session: Session) : AbstractDao<Actor, Int>(session, actorTable, Actor::id)

// Now we can use the DAO
val dao = ActorDao(session)
val inserted = dao.insert(Actor(1, Name("Kate", "Beckinsale"), LocalDateTime.now())
val actors = dao.findAll()
```

#### Mapping 1-1 and M-1 Relationships

Kwery imposes no constraints on your model. You can use flat record-like structures
or rich domain models. Your models may be mutable or immutable.

With Kotlin, you must decide whether your model values are nullable or not. If you model
them to match your domain (and database constraints) then you'll have not null
values in your model.

However, this is problematic in a couple of cases:

1. Your query partially selects values from your model and excludes a mandatory value
2. You construct a reference to another object and all you have is the foreign key id

You can make all your model attributes nullable but this is pretty much unusable in
Kotlin.

For the case of partially selecting values, Kwery takes the approach of supplying
default values in place of `null`. This requires that your column definitions supply
a default value (this is usually done implicitly for standard types).

For the case of constructing references to you need to supply a constructor or
factory function that can construct the value with just the id.

Given the above constraints, I tend to model my domain as immutable classes
with default values as follows:

```kotlin
data class Film(
    val id: Int = 0,
    val language: Language = Language(0),
    val originalLanguage: Language? = null
)

data class Language(
    val id: Int = 0,
    val name: String = "")
```

The only nullable values are those that are nullable in the domain (and database) -
all other values have defaults.

With the domain model above, there are two approaches to mapping it with Kwery to the
following table definition:

```SQL
create table film (
    id                   integer identity,
    language_id          integer not null, -- FK to language table
    original_language_id integer           -- FK to language table
)
```

##### Mapping the structure

Mapping the structure is the easiest approach if the related type (`Language` in this case) is used
infrequently.

This involves using `col` and `optionalCol` variants that allow you to specify that
the column maps to value of another object (`Language::id`) via a function that
returns the nested object (`Film::language` and `Film::originalLanguage`)

The `create` function must then construct the nested `Language` objects from
the ids (taking care in the nullable case).

```kotlin
object filmTable1 : Table<Film, Int>("film") {
    val Id                 by col(Film::id, id = true)
    val LanguageId         by col(Language::id, Film::language)
    val OriginalLanguageId by optionalCol(Language::id, Film::originalLanguage)

    override fun idColumns(id: Int) = setOf(Id of id)

    override fun create(value: Value<Film>): Film =
            Film(value of Id, Language(value of LanguageId),
            (value of OriginalLanguageId)?.let { Language(it) })
}

class FilmDao1(session: Session) : AbstractDao<Film, Int>(
        session, filmTable1, Film::id, "int", defaultId = 0)
```

##### Mapping with defaults and converters

Mapping the structure is workable, but there is a cleaner way of mapping the `Language`
column types at the expense of defining a `TableConfiguration` that includes `defaults` and `converters`.

```kotlin
val tableConfig = TableConfiguration(
        defaults = standardDefaults + reifiedValue(Language(0)),
        converters = standardConverters + reifiedConverter(languageConverter)
)
```

The default defined above is fairly self explanatory - use the value `Language(0)`
as a default for any value of type `Language`.

The `converter` defines how a `Language` type can be mapped to and from a column.

```kotlin
object languageConverter : SimpleConverter<Language>(
        { row, c -> Language(row.int(c)) },
        Language::id
)
```

Now the table object can be created using the custom `tableConfig`, allowing
us to simplify the mapping. Columns now map directly to `Film` fields, not 
`Language` fields and the `create` method now gets `Language` objects instead
of `ints`.
 
```kotlin
object filmTable2 : Table<Film, Int>("film", tableConfig) {
    val Id                 by col(Film::id, id = true)
    val LanguageId         by col(Film::language)
    val OriginalLanguageId by col(Film::originalLanguage)

    override fun idColumns(id: Int) = setOf(Id of id)

    override fun create(value: Value<Film>): Film =
            Film(value of Id, value of LanguageId, value of OriginalLanguageId)
}

class FilmDao2(session: Session) : AbstractDao<Film, Int>(
session, filmTable2, Film::id, "int", defaultId = 0)
```

Both approaches result in the same functionality. However, using defaults and converters
is cleaner, clearer and less error prone (and is therefore recommended).

The source for the above example is available [here](src/test/kotlin/com/github/andrewoma/kwery/mappertest/readme/Readme.kt).