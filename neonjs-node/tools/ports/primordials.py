NOTE = 'the object is made here and exported rather than filled in as a global'
REPLACEMENTS = [
    ("""'use strict';

/* eslint-disable node-core/prefer-primordials */
""", """'use strict';

/* eslint-disable node-core/prefer-primordials */

const primordials = {};
"""),
]
APPEND = """
module.exports = primordials;
"""
