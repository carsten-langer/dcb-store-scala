package dcbstore.inmemdcbstore

final case class InMemAppendConditionFailed()
  extends RuntimeException("Append condition failed")
