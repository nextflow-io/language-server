/*
 * Copyright 2013-2024, Seqera Labs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nextflow.config.scopes;

import nextflow.config.dsl.ConfigOption;
import nextflow.config.dsl.ConfigScope;

public class MailSmtpConfig implements ConfigScope {

    public MailSmtpConfig() {}

    @Override
    public String name() {
        return "mail.smtp";
    }

    @Override
    public String description() {
        return """
            The `mail.smtp` scope supports any SMTP configuration property in the [Java Mail API](https://javaee.github.io/javamail/).

            [Read more](https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html#properties)
            """;
    }

    @ConfigOption("""
        Host name of the mail server.
        """)
    public String host;

    @ConfigOption("""
        User password to connect to the mail server.
        """)
    public String password;

    @ConfigOption("""
        Port number of the mail server.
        """)
    public int port;

    @ConfigOption("""
        User name to connect to the mail server.
        """)
    public String user;

}
