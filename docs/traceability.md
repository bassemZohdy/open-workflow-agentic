# Traceability

Requirement to test coverage and implementation status. Generated; do not edit.

## Coverage summary

| Status | Tests |
|---|---:|
| not-started | 34 |
| planned | 5 |

## Requirement coverage

| Requirement | Level | Tests | Best test status |
|---|---|---|---|
| M01 Process semantics and definition validation | Mandatory | T01, T29 | not-started |
| M02 Bounded agent autonomy and risk controls | Mandatory | T01, T06, T25, T33, T34 | planned |
| M03 Durable execution and checkpoint recovery | Mandatory | T08, T12, T18, T26, T37 | planned |
| M04 Durable triggers, timers and lifecycle | Mandatory | T12, T13 | planned |
| M05 Authorized human interaction | Mandatory | T02, T12, T37 | planned |
| M06 Events, business effects and compensation | Mandatory | T03, T13, T27 | planned |
| M07 Resilience under dependency failure and overload | Mandatory | T03, T17 | not-started |
| M08 Backup, restore and disaster recovery | Mandatory | T18 | not-started |
| M09 Version-safe in-flight work | Mandatory | T14, T28, T32 | not-started |
| M10 Governed publication and activation | Mandatory | T14, T19, T30, T31 | not-started |
| M11 Authentication, delegation and secret handling | Mandatory | T02, T07, T15, T19, T36 | not-started |
| M12 External policy and safe denial | Mandatory | T01, T07, T23 | not-started |
| M13 Data security, privacy and location | Mandatory | T20 | not-started |
| M14 Untrusted inputs and optional code execution | Mandatory | T01, T09, T25, T35 | not-started |
| M15 Auditability and AI decision provenance | Mandatory | T04, T31, T34 | planned |
| M16 Observability and operational APIs | Mandatory | T04, T16 | planned |
| M17 AI change evaluation and model continuity | Mandatory | T10, T11, T31, T38, T39 | not-started |
| M18 Supply-chain integrity and AI inventory | Mandatory | T10, T19, T31 | not-started |
| M19 Agent context, memory and state governance | Mandatory | T08, T15, T26 | not-started |
| M20 Emergency containment and AI incident handling | Mandatory | T11, T23, T31, T38 | not-started |
| M21 Safe platform upgrades | Mandatory | T24, T28 | not-started |
| M22 Provider-independent deployability | Mandatory | T21, T39 | not-started |
| M23 Enterprise and external-agent interoperability | Mandatory | T05, T27 | not-started |
| M24 Tenant safety where used | Mandatory | T20 | not-started |
| M25 Reproducible authoring and testable artifacts | Mandatory | T29, T33 | not-started |
| M26 Separation of authority | Mandatory | T22, T30, T33 | not-started |
| M27 Safe autonomy during administration outages | Mandatory | T22, T32 | not-started |
| M28 Recoverable canonical progress and reconciliation | Mandatory | T08, T16, T17, T26, T36 | planned |
| M29 Goal-driven planning and adaptive execution | Mandatory | T34 | planned |
| M30 Tool, knowledge and unstructured-input use | Mandatory | T35 | not-started |
| M31 Multi-agent collaboration and delegation | Mandatory | T36 | not-started |
| M32 Interactive human-agent collaboration | Mandatory | T37 | planned |
| M33 Agent quality monitoring in production | Mandatory | T38 | not-started |
| M34 Model choice and routing per agent task | Mandatory | T39 | not-started |
| H01 Open Workflow Specification alignment | High | - | no test |
| H02 Runtime and framework independence | High | - | no test |
| H03 Native MCP and A2A support | High | - | no test |
| H04 High-volume scaling | High | - | no test |
| H05 Administration and business task UI | High | - | no test |
| H06 Advanced AI explanations | High | - | no test |
| H07 Event broker integration | High | - | no test |
| H08 Advanced replay and simulation | High | - | no test |
| H09 Advanced cost and model-usage governance | High | - | no test |
| H10 Automatic in-flight definition migration | High | - | no test |
| H11 Zero-downtime upgrade | High | - | no test |
| H12 Developer lifecycle and packaging UX | High | - | no test |
| H13 Advanced AI quality analytics | High | - | no test |
| H14 Advanced scheduling calendars | High | - | no test |
| H15 Strong workload isolation and cells | High | - | no test |
| H16 Advanced tenancy administration | High | - | no test |
| H17 Dynamic activation | High | - | no test |
| H18 AI-specific incident and red-team operations | High | - | no test |
| H19 Legacy transition and coexistence | High | - | no test |
| H20 Multilingual experience | High | - | no test |
| H21 Multiple execution runtimes | High | - | no test |
| H22 Visual/non-developer authoring | High | - | no test |
| H23 Open-source licensing and reuse freedom | High | - | no test |
| H24 Simple, clean and maintainable architecture | High | - | no test |
| H25 Long-term agent memory and personalization | High | - | no test |
| O01 Embedded governance assistant | Optional | - | no test |
| O02 Separate hosted Development Plane | Optional | - | no test |
| O03 Physical cell-based architecture | Optional | - | no test |
| O04 Full hot-swap without restart | Optional | - | no test |
| O05 Full custom orchestration engine | Optional | - | no test |

## Requirements without a dedicated acceptance test

H01, H02, H03, H04, H05, H06, H07, H08, H09, H10, H11, H12, H13, H14, H15, H16, H17, H18, H19, H20, H21, H22, H23, H24, H25, O01, O02, O03, O04, O05

Requirements without a test are not untested by intent: High and Optional items are normally verified through design review or the evidence listed in the requirement itself.
