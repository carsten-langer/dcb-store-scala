Feature: Dynamic Consistency Boundary event store

  A DCB store stores events in a single global sequence.
  Events can be queried by event type and tags.
  Append conditions prevent appending events when conflicting events were appended meanwhile.

  Background:
    Given an empty DCB store

  Scenario: Reading from an empty store returns no events
    When I read all events
    Then the read result should contain 0 events

  Scenario: Appending a single event and reading all events
    When I append the following events without an append condition:
      | name            | type           | tags       | data               |
      | student-created | StudentCreated | student:s1 | student s1 created |
    Then the append should succeed
    When I read all events
    Then the read result should contain the following events in order:
      | name            |
      | student-created |

  Scenario: Appending multiple events preserves their order
    When I append the following events without an append condition:
      | name            | type              | tags                  | data                |
      | student-created | StudentCreated    | student:s1            | student s1 created  |
      | course-created  | CourseCreated     | course:c1             | course c1 created   |
      | subscribed      | StudentSubscribed | student:s1, course:c1 | s1 subscribed to c1 |
    Then the append should succeed
    When I read all events
    Then the read result should contain the following events in order:
      | name            |
      | student-created |
      | course-created  |
      | subscribed      |

  Scenario: Reading events by event type
    Given the following events have been appended:
      | name            | type              | tags                  | data                |
      | student-created | StudentCreated    | student:s1            | student s1 created  |
      | course-created  | CourseCreated     | course:c1             | course c1 created   |
      | subscribed      | StudentSubscribed | student:s1, course:c1 | s1 subscribed to c1 |
    When I read events matching event type "StudentSubscribed"
    Then the read result should contain the following events in order:
      | name       |
      | subscribed |

  Scenario: Reading events by tag
    Given the following events have been appended:
      | name             | type              | tags                  | data                |
      | student-created  | StudentCreated    | student:s1            | student s1 created  |
      | course-created   | CourseCreated     | course:c1             | course c1 created   |
      | s1-subscribed-c1 | StudentSubscribed | student:s1, course:c1 | s1 subscribed to c1 |
      | s2-subscribed-c1 | StudentSubscribed | student:s2, course:c1 | s2 subscribed to c1 |
      | s1-subscribed-c2 | StudentSubscribed | student:s1, course:c2 | s1 subscribed to c2 |
    When I read events matching tag "student:s1"
    Then the read result should contain the following events in order:
      | name             |
      | student-created  |
      | s1-subscribed-c1 |
      | s1-subscribed-c2 |

  Scenario: Reading events by event type and tag
    Given the following events have been appended:
      | name               | type                | tags                  | data                    |
      | student-created    | StudentCreated      | student:s1            | student s1 created      |
      | s1-subscribed-c1   | StudentSubscribed   | student:s1, course:c1 | s1 subscribed to c1     |
      | s2-subscribed-c1   | StudentSubscribed   | student:s2, course:c1 | s2 subscribed to c1     |
      | s1-unsubscribed-c1 | StudentUnsubscribed | student:s1, course:c1 | s1 unsubscribed from c1 |
    When I read events matching event type "StudentSubscribed" and tag "student:s1"
    Then the read result should contain the following events in order:
      | name             |
      | s1-subscribed-c1 |

  Scenario: A query item with multiple tags requires all tags to match
    Given the following events have been appended:
      | name             | type              | tags                  | data                |
      | student-created  | StudentCreated    | student:s1            | student s1 created  |
      | course-created   | CourseCreated     | course:c1             | course c1 created   |
      | s1-subscribed-c1 | StudentSubscribed | student:s1, course:c1 | s1 subscribed to c1 |
      | s1-subscribed-c2 | StudentSubscribed | student:s1, course:c2 | s1 subscribed to c2 |
      | s2-subscribed-c1 | StudentSubscribed | student:s2, course:c1 | s2 subscribed to c1 |
    When I read events matching tags:
      | tag        |
      | student:s1 |
      | course:c1  |
    Then the read result should contain the following events in order:
      | name             |
      | s1-subscribed-c1 |

  Scenario: A query with multiple query items matches events that match any item
    Given the following events have been appended:
      | name             | type              | tags                  | data                |
      | student-created  | StudentCreated    | student:s1            | student s1 created  |
      | course-created   | CourseCreated     | course:c1             | course c1 created   |
      | s1-subscribed-c1 | StudentSubscribed | student:s1, course:c1 | s1 subscribed to c1 |
      | s2-subscribed-c2 | StudentSubscribed | student:s2, course:c2 | s2 subscribed to c2 |
    When I read events matching any of the following query items:
      | eventTypes     | tags       |
      | StudentCreated | student:s1 |
      | CourseCreated  | course:c1  |
    Then the read result should contain the following events in order:
      | name            |
      | student-created |
      | course-created  |

  Scenario: Append condition succeeds when no existing event matches the condition query
    Given the following events have been appended:
      | name            | type           | tags       | data               |
      | student-created | StudentCreated | student:s1 | student s1 created |
    When I append the following events with append condition matching tag "course:c1":
      | name           | type          | tags      | data              |
      | course-created | CourseCreated | course:c1 | course c1 created |
    Then the append should succeed
    When I read all events
    Then the read result should contain the following events in order:
      | name            |
      | student-created |
      | course-created  |

  Scenario: Append condition fails when an existing event matches the condition query
    Given the following events have been appended:
      | name           | type          | tags      | data              |
      | course-created | CourseCreated | course:c1 | course c1 created |
    When I append the following events with append condition matching tag "course:c1":
      | name       | type              | tags                  | data                |
      | subscribed | StudentSubscribed | student:s1, course:c1 | s1 subscribed to c1 |
    Then the append should fail
    When I read all events
    Then the read result should contain the following events in order:
      | name           |
      | course-created |

  Scenario: Append condition only checks events matching its query
    Given the following events have been appended:
      | name            | type           | tags       | data               |
      | student-created | StudentCreated | student:s1 | student s1 created |
    When I append the following events with append condition matching tag "course:c1":
      | name           | type          | tags      | data              |
      | course-created | CourseCreated | course:c1 | course c1 created |
    Then the append should succeed
    When I read all events
    Then the read result should contain the following events in order:
      | name            |
      | student-created |
      | course-created  |

  Rule: Append conditions are respected.
  Note how append conditions technically only use another query. The typical use case would be to re-use the same query
  that was used for the write model. However, in the following tests the condition is explicitly built to be a query
  only on a matching tag. This may feel strange from a domain perspective, but is enough to test the "after" feature.

    Scenario: Append condition with after succeeds when matching event is at the after position

      Given the following events have been appended:
        | name            | type           | tags       | data               |
        | student-created | StudentCreated | student:s1 | student s1 created |
      When I append the following events with append condition matching tag "student:s1" after event matching event type "StudentCreated" and tag "student:s1":
        | name                 | type               | tags       | data                    |
        | student-name-changed | StudentNameChanged | student:s1 | student s1 name changed |
      Then the append should succeed
      When I read all events
      Then the read result should contain the following events in order:
        | name                 |
        | student-created      |
        | student-name-changed |

    Scenario: Append condition with after succeeds when matching event is before the after position
      Given the following events have been appended:
        | name            | type           | tags       | data               |
        | student-created | StudentCreated | student:s1 | student s1 created |
        | course-created  | CourseCreated  | course:c1  | course c1 created  |
      When I append the following events with append condition matching tag "student:s1" after event matching event type "CourseCreated" and tag "course:c1":
        | name                 | type               | tags       | data                    |
        | student-name-changed | StudentNameChanged | student:s1 | student s1 name changed |
      Then the append should succeed
      When I read all events
      Then the read result should contain the following events in order:
        | name                 |
        | student-created      |
        | course-created       |
        | student-name-changed |

    Scenario: Append condition with after fails when matching event is after the after position
      Given the following events have been appended:
        | name            | type           | tags       | data               |
        | course-created  | CourseCreated  | course:c1  | course c1 created  |
        | student-created | StudentCreated | student:s1 | student s1 created |
      When I append the following events with append condition matching tag "student:s1" after event matching event type "CourseCreated" and tag "course:c1":
        | name                  | type           | tags       | data               |
        | student-created-again | StudentCreated | student:s1 | student s1 created |
      Then the append should fail
      When I read all events
      Then the read result should contain the following events in order:
        | name            |
        | course-created  |
        | student-created |

    Scenario: Append condition with after ignores non-matching events after the after position
      Given the following events have been appended:
        | name                  | type           | tags       | data               |
        | student-created       | StudentCreated | student:s1 | student s1 created |
        | course-created        | CourseCreated  | course:c1  | course c1 created  |
        | other-student-created | StudentCreated | student:s2 | student s2 created |
      When I append the following events with append condition matching tag "student:s1" after event matching event type "CourseCreated" and tag "course:c1":
        | name       | type              | tags                  | data                |
        | subscribed | StudentSubscribed | student:s1, course:c1 | s1 subscribed to c1 |
      Then the append should succeed
      When I read all events
      Then the read result should contain the following events in order:
        | name                  |
        | student-created       |
        | course-created        |
        | other-student-created |
        | subscribed            |
