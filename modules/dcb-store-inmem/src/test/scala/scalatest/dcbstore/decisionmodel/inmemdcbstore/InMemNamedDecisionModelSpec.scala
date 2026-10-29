package scalatest.dcbstore.decisionmodel.inmemdcbstore

import dcbstore.inmemdcbstore.InMemDcbStore
import scalatest.dcbstore.decisionmodel.NamedDecisionModelSpec

class InMemNamedDecisionModelSpec
  extends NamedDecisionModelSpec(() => InMemDcbStore())
