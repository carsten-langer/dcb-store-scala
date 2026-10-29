package scalatest.dcbstore.decisionmodel.inmemdcbstore

import dcbstore.inmemdcbstore.InMemDcbStore
import scalatest.dcbstore.decisionmodel.ComposedDecisionModelSpec

class InMemComposedDecisionModelSpec
  extends ComposedDecisionModelSpec(() => InMemDcbStore())
