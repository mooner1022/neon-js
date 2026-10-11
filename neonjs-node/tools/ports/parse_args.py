NOTE = "there is no command line here: the arguments are process.argv's after the first two"
REPLACEMENTS = [
    ("const { getOptionValue } = require('internal/options');",
     "// no --eval here\nconst getOptionValue = () => '';"),
]
