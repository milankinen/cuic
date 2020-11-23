## Usage

The main functionality of `cuic` is located in the `cuic.core` namespace,
which contains functions for element queries and page interactions. The basic
code flow can be divided roughtly into two parts: (1) querying html elements
and (2) interacting with the queried element. 

In addition to `cuic.core`, `cuic` has the following supplementary namespaces: 

  * `cuic.test` providing some convenience utilities for tests assertions 
  * `cuic.chrome` for Chrome instance launching and management
