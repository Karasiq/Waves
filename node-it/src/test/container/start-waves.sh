#!/bin/bash
echo Options: $WAVES_OPTS
exec java $WAVES_OPTS -cp "/opt/waves/lib/*" com.wavesplatform.data.VolkMain
