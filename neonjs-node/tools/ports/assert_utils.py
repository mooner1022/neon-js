NOTE = ("the failing expression is not quoted (Node reads it from V8's source positions): assert.ok's generated "
        "message is then 'false == true'")
REPLACEMENTS = [
    ("""const {
  getErrorSourceExpression,
} = require('internal/errors/error_source');""",
     """// no source positions here: no expression to quote
const getErrorSourceExpression = () => undefined;"""),
    ("""      messageArgs = [getErrMessage(fn)];""",
     """      const source = getErrMessage(fn);
      messageArgs = source === undefined ? [] : [source];"""),
]
