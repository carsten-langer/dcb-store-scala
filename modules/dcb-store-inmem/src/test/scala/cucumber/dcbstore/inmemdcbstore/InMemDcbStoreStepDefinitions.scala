package cucumber.dcbstore.inmemdcbstore

import cucumber.dcbstore.DcbStoreStepDefinitions
import dcbstore.inmemdcbstore.InMemDcbStore

class InMemDcbStoreStepDefinitions
  extends DcbStoreStepDefinitions(InMemDcbStore())
