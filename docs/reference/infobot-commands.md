# Infobot Commands

This reference is intentionally high-level while command docs are being reorganized from the old documentation set.

The bundled infobot currently includes capabilities from modules such as:

| Capability | Examples |
|------------|----------|
| Calculator | `calc 2+3` |
| Factoids | `spring`, `spring is A Java framework`, `spring.tags=java,framework` |
| Karma | `kotlin++`, `xml--` |
| Specs | `rfc 2616`, `jep 456`, `jsr 330`, `pep 8` |
| Dictionary | `define ubiquitous` |
| GitHub/RSS | repository and feed subscription commands |
| URL titles | paste a URL and receive a title |

Most commands require addressing through the protocol adapter, such as `!calc 2+3` or a bot mention. Some operations are unaddressed and watch ambient conversation.

Detailed command reference should be rebuilt from the operation modules and the old `../../q/docs/user-guide.md` material.
