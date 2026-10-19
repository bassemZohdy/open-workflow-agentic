# Traceability

Requirement to test coverage and implementation status. Generated; do not edit.

The best test status below is not full requirement closure. See [TODO.md](../TODO.md#requirement-coverage) for implemented scope and remaining work.

## Coverage summary

| Status | Tests |
|---|---:|
| automated | 2 |
| not-started | 32 |
| planned | 5 |

## Requirement coverage

| Requirement | Level | Tests | Work packages | Best test status |
|---|---|---|---|---|
| M01 Process semantics and definition validation | Mandatory | T01, T29 | WP-101, WP-104, WP-601 | automated |
| M02 Bounded agent autonomy and risk controls | Mandatory | T01, T06, T25, T33, T34 | WP-101, WP-104, WP-202, WP-301, WP-601 | automated |
| M03 Durable execution and checkpoint recovery | Mandatory | T08, T12, T18, T26, T37 | WP-003, WP-004, WP-005, WP-109, WP-201, WP-208 | planned |
| M04 Durable triggers, timers and lifecycle | Mandatory | T12, T13 | WP-003, WP-105, WP-107, WP-109 | planned |
| M05 Authorized human interaction | Mandatory | T02, T12, T37 | WP-106, WP-208 | planned |
| M06 Events, business effects and compensation | Mandatory | T03, T13, T27 | WP-107, WP-108, WP-602 | planned |
| M07 Resilience under dependency failure and overload | Mandatory | T03, T17 | WP-108, WP-110 | not-started |
| M08 Backup, restore and disaster recovery | Mandatory | T18 | WP-401 | not-started |
| M09 Version-safe in-flight work | Mandatory | T14, T28, T32 | WP-003, WP-302 | not-started |
| M10 Governed publication and activation | Mandatory | T14, T19, T30, T31 | WP-303 | not-started |
| M11 Authentication, delegation and secret handling | Mandatory | T02, T07, T15, T19, T36 | WP-005, WP-006, WP-102, WP-207 | automated |
| M12 External policy and safe denial | Mandatory | T01, T07, T23 | WP-006, WP-103 | automated |
| M13 Data security, privacy and location | Mandatory | T20 | WP-306 | not-started |
| M14 Untrusted inputs and optional code execution | Mandatory | T01, T09, T25, T35 | WP-104, WP-204, WP-205, WP-209 | automated |
| M15 Auditability and AI decision provenance | Mandatory | T04, T31, T34 | WP-101, WP-111, WP-203 | planned |
| M16 Observability and operational APIs | Mandatory | T04, T16 | WP-105, WP-112 | planned |
| M17 AI change evaluation and model continuity | Mandatory | T10, T11, T31, T38, T39 | WP-501, WP-503 | not-started |
| M18 Supply-chain integrity and AI inventory | Mandatory | T10, T19, T31 | WP-301, WP-303, WP-307 | not-started |
| M19 Agent context, memory and state governance | Mandatory | T08, T15, T26 | WP-004, WP-201, WP-206 | not-started |
| M20 Emergency containment and AI incident handling | Mandatory | T11, T23, T31, T38 | WP-305 | not-started |
| M21 Safe platform upgrades | Mandatory | T24, T28 | WP-402 | not-started |
| M22 Provider-independent deployability | Mandatory | T21, T39 | WP-001, WP-403, WP-503 | not-started |
| M23 Enterprise and external-agent interoperability | Mandatory | T05, T27 | WP-007, WP-113, WP-204, WP-207, WP-602 | not-started |
| M24 Tenant safety where used | Mandatory | T20 | WP-404 | not-started |
| M25 Reproducible authoring and testable artifacts | Mandatory | T29, T33 | WP-601 | not-started |
| M26 Separation of authority | Mandatory | T22, T30, T33 | WP-001, WP-304, WP-601 | not-started |
| M27 Safe autonomy during administration outages | Mandatory | T22, T32 | WP-304 | not-started |
| M28 Recoverable canonical progress and reconciliation | Mandatory | T08, T16, T17, T26, T36 | WP-005, WP-105, WP-203 | planned |
| M29 Goal-driven planning and adaptive execution | Mandatory | T34 | WP-201, WP-203 | planned |
| M30 Tool, knowledge and unstructured-input use | Mandatory | T35 | WP-204, WP-205 | not-started |
| M31 Multi-agent collaboration and delegation | Mandatory | T36 | WP-207 | not-started |
| M32 Interactive human-agent collaboration | Mandatory | T37 | WP-004, WP-007, WP-208 | planned |
| M33 Agent quality monitoring in production | Mandatory | T38 | WP-502 | not-started |
| M34 Model choice and routing per agent task | Mandatory | T39 | WP-503 | not-started |
| H01 Open Workflow Specification alignment | High | - | WP-003, WP-605 | no test |
| H02 Runtime and framework independence | High | - | WP-605 | no test |
| H03 Native MCP and A2A support | High | - | WP-007, WP-406 | no test |
| H04 High-volume scaling | High | - | WP-405 | no test |
| H05 Administration and business task UI | High | - | WP-603 | no test |
| H06 Advanced AI explanations | High | - | WP-606 | no test |
| H07 Event broker integration | High | - | WP-406 | no test |
| H08 Advanced replay and simulation | High | - | WP-606 | no test |
| H09 Advanced cost and model-usage governance | High | - | WP-504 | no test |
| H10 Automatic in-flight definition migration | High | - | deferred | no test |
| H11 Zero-downtime upgrade | High | - | WP-402 | no test |
| H12 Developer lifecycle and packaging UX | High | - | WP-002, WP-601 | no test |
| H13 Advanced AI quality analytics | High | - | WP-502 | no test |
| H14 Advanced scheduling calendars | High | - | WP-405 | no test |
| H15 Strong workload isolation and cells | High | - | deferred | no test |
| H16 Advanced tenancy administration | High | - | deferred | no test |
| H17 Dynamic activation | High | - | deferred | no test |
| H18 AI-specific incident and red-team operations | High | - | deferred | no test |
| H19 Legacy transition and coexistence | High | - | WP-602 | no test |
| H20 Multilingual experience | High | - | deferred | no test |
| H21 Multiple execution runtimes | High | - | deferred | no test |
| H22 Visual/non-developer authoring | High | - | WP-604 | no test |
| H23 Open-source licensing and reuse freedom | High | - | WP-001, WP-307 | no test |
| H24 Simple, clean and maintainable architecture | High | - | WP-001 | no test |
| H25 Long-term agent memory and personalization | High | - | WP-206 | no test |
| O01 Embedded governance assistant | Optional | - | WP-604 | no test |
| O02 Separate hosted Development Plane | Optional | - | deferred | no test |
| O03 Physical cell-based architecture | Optional | - | deferred | no test |
| O04 Full hot-swap without restart | Optional | - | deferred | no test |
| O05 Full custom orchestration engine | Optional | - | deferred | no test |

## Requirements without a dedicated acceptance test

H01, H02, H03, H04, H05, H06, H07, H08, H09, H10, H11, H12, H13, H14, H15, H16, H17, H18, H19, H20, H21, H22, H23, H24, H25, O01, O02, O03, O04, O05

Requirements without a test are not untested by intent: High and Optional items are normally verified through design review or the evidence listed in the requirement itself.
