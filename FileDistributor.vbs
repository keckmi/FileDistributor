' Unicode-safe launcher for File Distributor
' Put this .vbs next to your file-distributor.jar
' and point your SendTo shortcut at it.

Dim shell, cmd, i
Set shell = CreateObject("WScript.Shell")

' Path to Java and JAR
cmd = Chr(34) & "C:\Program Files\Java\jdk-21\bin\javaw.exe" & Chr(34) & _
      " -jar " & Chr(34) & "C:\Users\ASUS\VisualStudioCodeProjects\file-distributor\file-distributor.jar" & Chr(34)

' Append all incoming file paths, quoted (VBScript keeps them as Unicode)
For i = 0 To WScript.Arguments.Count - 1
    cmd = cmd & " " & Chr(34) & WScript.Arguments(i) & Chr(34)
Next

' 0 = hidden window, False = don’t wait for Java process
shell.Run cmd, 0, False