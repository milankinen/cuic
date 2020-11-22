## Usage

The main functionality of `cuic` is located in the `cuic.core` namespace,
which contains functions for dom queries and dom node interactions. The basic
code flow can be divided roughtly into two parts: (1) querying dom nodes
and (2) interacting with the queried nodes. 

In addition to `cuic.core`, `cuic` has the following supplementary namespaces: 

  * `cuic.test` providing some convenience utilities for tests assertions 
  * `cuic.chrome` for Chrome instance launching and management
