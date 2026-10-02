# Conventions

## Comments explain *why*, not *what*

The existing code consistently documents the reason a line is the way it is,
usually naming the failure it prevents. Match that style: a comment that only
restates the code should be deleted.

## Strings

* Every user-visible string lives in `res/values/strings.xml`. Default locale is
  **zh-TW**; `res/values-en/strings.xml` mirrors it in English.
* **Both files must be edited together.** A key added to only one will compile
  but silently fall back to the default locale.
* `StringResourceTest` asserts key parity and format-argument consistency. Adding
  a `%s`/`%d` to a string in one file but not the other fails the build.
* Never build UI text by concatenation; use format arguments.

## Compose

* **Never resolve an app label inside a composable.** `PackageManager` lookups on
  every recomposition are a real cost. Use the pre-resolved `RewardAppInfo.label`
  from `candidates` / `rewardApps`, and fall back to the raw package id.
* Screens take plain data + lambdas. State lives in `MainViewModel`; screens do
  not touch repositories or the database.
* Hoist anything that costs a system call to the top of the composition and pass
  it down (e.g. `ServiceAccess.isServiceEnabled(context)`).
* Use `key` in `LazyColumn` item loops that can reorder.

## ViewModels

* `MainViewModel` deliberately has **two constructors**:
  `(Application, SavedStateHandle)` and `(Application)`.
  Do **not** collapse them back into a Kotlin default argument
  (`handle: SavedStateHandle = SavedStateHandle()`): that compiles to a
  synthetic `(Application, SavedStateHandle, int, DefaultConstructorMarker)`
  signature, which the reflection-based `ViewModelProvider` factory cannot
  match — the ViewModel then cannot be created at all.
  `MainViewModelConstructorTest` pins this; it will fail if you regress it.

## Session manager

* All mutating methods are `@Synchronized`. They run on the accessibility
  callback thread, so they must stay cheap: **no I/O, no PackageManager**.
* `publish()` only ever reflects `currentSession`. When there is no session,
  `sourcePackage` must be null — do not fall back to the previous snapshot.

## Testing

* Test names are backtick sentences describing the behaviour, not the method.
* Seed a wrong-but-plausible value in `@Before` when testing that something is
  cleared, otherwise a test can pass simply because the field defaulted to null.
* Every bug fix gets a regression test, and that test must be shown to **fail**
  against the old code before being accepted.
