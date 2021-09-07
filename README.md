# guacamole-auth-libcalcas

This repository contains some simple modifications made to the 
[Guacamole CAS support](https://guacamole.apache.org/doc/gug/cas-auth.html)
for supporting _CAS_ with [LibCal](https://www.springshare.com/libcal/). Except
for _pom.xml_, the java files would be copied to the _CAS_ directory, and the
_Guacamole_ distribution includes its own licenses:

```
src/main/java/org/apache/guacamole/auth/cas
```

This is a maven project, as per _Guacamole's_ documentation, and the code can be 
cleaned/compiled/deployed with:

```
mvn clean
mvn package
sudo cp target/guacamole-auth-libcalcas-1.1.0.jar /etc/guacamole/extensions/
```

_Guacamole_ keeps settings in _/etc/guacamole/guacamole.properties_:

```
guacd-hostname: localhost
guacd-port:     4822
api-session-timeout: 5
cas-authorization-endpoint: https://somewhere.org/idp/profile/cas
cas-redirect-uri: https://somewhere.org/guacamole/
libcalcas-oauth-server: https://somewhere-org.libcal.com/1.1
libcalcas-client-id: 000
libcalcas-calendar-id: 0000
libcalcas-client-secret: 00...
libcalcas-session-mins: 150
libcalcas-invalid-uri: https://somewhere.org
```
These are mostly self-explanatory. Authentication is done via _CAS_, and the _LibCal_
settings will be familiar for those who have worked with the API. Stations are 
identified by their _LibCal id_, 
which gets mapped to _/etc/guacamole/user-mapping.xml_ as shown:

```
<authorize username="00000" password="00000">
    <!-- WK01 -->
    <connection name="WK 01">
        <protocol>rdp</protocol>
        <param name="hostname">wk01.somewhere.org</param>
        <param name="port">3389</param>
        <param name="username">modify</param>
        <param name="password">modify</param>
        <param name="ignore-cert">true</param>
    </connection>
</authorize>
```

So in this case, the LibCal id for the station is _00000_. There are probably 
better ways to associate sessions with 
RDP accounts but this allows changes without modifying any code.

The bookable windows desktop has no knowledge of _Guacamole_, this is literally a regular 
[RDP](https://en.wikipedia.org/wiki/Remote_Desktop_Protocol) session to it. There are a set of 
scheduled tasks that enforce the time limits prescribed in the calendar, as shown below

<img src="https://github.com/ledwebdev/guacamole-auth-libcalcas/blob/main/libcal1.png?raw=true" width="50%" height="50%">

These correspond to the available slots, in this case, 7-10 am:

| Name/Time | Script | Commands |
| --- | --- | --- |
| 9_25am | c:\scripts\rdpwarn.bat | @echo off <br /> echo Msg pc will shut down in 5 minutes <br /> msg * /server:127.0.0.1 "WARNING - This session is almost ever. This PC will start a shutdown routine in 5 minutes. Thank you." |
| 9_30am | c:\scripts\netoff.bat | @echo off <br /> netsh interface set interface "Ethernet" admin=disable |
| 9_45am | c:\scripts\rdpoff.bat | @echo off <br /> netsh interface set interface "Ethernet" admin=enable <br /> shutdown /r /f /t 120 /c "This session is now over. This PC will power off in 2 minutes without any more warnings. Thank you." |

The 9:25 scheduled task gives a warning that the session is almost over. The 9:30 task disables the network 
connection. It is important that the timing of this is longer than the length of the session timeout 
defined for _Guacamole_ (_api-session-timeout_: 5), since the RDP client will try to automatically 
reconnect and this will stop a session booking from overlapping with another. The 9:45 task re-enables 
the network and causes a reboot. 

This work was done for the awesome folks at the [Academic Data Centre](https://leddy.uwindsor.ca/key-service-areas/academic-data-centre)
at the [University of Windsor](https://www.uwindsor.ca).

art rhyno [ourdigitalworld/cdigs](https://github.com/artunit)
