This example demonstrates how the automatic reloading works during development.

There are no automated tests for this.

Instructions:

- start the example via `./mill -w examples.reloader.runBackground`
- open a browser
- edit the example code, save, and watch the browser reload

Stop the background task:

`./mill clean examples.reloader.runBackground`
