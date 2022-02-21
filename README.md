# Fallback Jmod

This is a prototype to explore the possibilities of Jmod file format evolution.

The work that has been achieved so far is to greatly reduce the size of the JDK by reducing Jmod.

With [SapMachine OpenJDK](https://sap.github.io/SapMachine/) as the test case, 
it can achieve the goal without losing any function:

* For `sapmachine-jdk-17.0.2.tar.gz` compressed packages: **184MB -> 115M**
* For the extracted directory: **316MB -> 242M**

The reason for getting such a size reduction is that we reduced the Jmod file from 77MB to 2.4MB.

To know why I'm able to do such amazing things, you need to know some facts first:

* **Jmod files are never used at execution time, it's only used on Jlink.**
* Most of the files in Jmod files are actually already included in the JDK.
  (Most of the files are located directly in the JDK folder, and `classes` are located in the JImage file `${java.home}/lib/modules`)

Based on this, I can avoid redundant storage of the same file by recording a list of files and its hash values in Jmod file.

Fallback Jmod works as follows:

First, it needs to process Jmod based on a "runtime path". Typically, the runtime path is a JDK or JRE path.

It has three modes:

**Reduce mode**: It traverses the Jmod files, and for the content in sections `bin`, `lib`, and `classes`, 
it looks up the corresponding files in the runtime path according to the same rules as Jlink.

If the corresponding file exists in the runtime path, it will calculate the SHA-256 hash value of the file in Jmod and the corresponding file at the same time, 
if it matches, remove the file in Jmod and leave a hash in `fallback.list` a record of hashes and paths.
`fallback.list` will be placed in the `classes` section of the Jmod file.

**Restore mode**: This is the inverse of reduce mode. It reads the hash and the path recorded in `fallback.list`.
If a recorded file doesn't exist in the Jmod file, it will look the verify by hash value for them in the runtime path.
If it cannot be found in the runtime path either, or if its hash value is different from what is recorded, 
the tool will report the problem and abort the restore process.

Finally, the tool will remove `fallback.list`, making it back to normal Jmod file.

**Jlink mode**: A Jlink plugin is implemented here, which enables the Jlink tool to generate the runtime with some fallback jmod file completely transparently, 
without having to restore first.

Since Jlink only loads builtin plugins by default, I provide a new mode for command line tools that injects the plugin into the Jlink process and calls Jlink.

## Usage

You can download it from [GitHub Release](https://github.com/Glavo/fallback-jmod/releases) and execute it with `java -jar fallback-jmod.jar (reduce|restore|jlink) <options> <jmod files>`.
It has no external dependencies and runs on JDK 11 and above.

It accepts the following command line arguments:

* `--output`/`-d`: Specify the output path.
  For the `reduce` and `restore` modes, the source Jmod files are overwritten by default, you can use this option to keep the source files and place the reduced or restored Jmod files in the directory you specify;
  For the `jlink` mode, you must specify this option to place the generated runtime image. It must not exist or be an empty folder in jlink mode.
* `--runtime-path`/`-p`: Specify the runtime path described above.
  If all Jmod files you specify are in the same folder, the default value of this option is the parent folder of that folder;  
  Otherwise, you must specify the runtime path explicitly.
* `--exclude`: (`reduce` mode only)  Specify files that should not be reduced.
  It accepts a glob list divided by `:`, and the path separator uses `/`. (It is implemented internally through the `ZipFileSystem::getPathMatcher`)
  When the path matches the glob, the file guaranteed **not to be reduced**.
* `--include-without-verify`: (`reduce` mode only) Specifies that the file will not be hash verified during reduction.
  It accepts a glob list divided by `:`, and the path separator uses `/`. (It is implemented internally through the `ZipFileSystem::getPathMatcher`)
  When the path matches the glob, as long as the file exists in the runtime path, we will reduce the file without requiring the content to match exactly.
  We will not record its hash value and skip the verification of its hash value in restore mode.

After the option is a list of Jmod files, declaring the Jmod files you want to process.
In this list, you can use `*` as a wildcard at the end of the path to specify all Jmod files within that folder.
(It's not a glob, you can only use it at the end of a path, and it doesn't recursively process subfolders.)

It's worth noting that the jlink mode, like any other mode, handles the list of Jmod files you specify.
This is different from the jlink command line tool using `--module-path` and `--add-modules`.

## JDK release

The purpose of this exploration is to hopefully improve the way the JDK is released.

The current JDK release method wastes a lot of storage space and network traffic.
For CI and other containers, this can be more wasteful than expected.

Moreover, the Jlink tool can generate the runtime image of other platform based on the the corresponding Jmod files, 
but the current distribution method causes us to download the complete JDK in order to download Jmod, which is a greater waste.

The ultimate goal of this exploration is to hope that the distribution method of OpenJDK will be improved:
Only Fallback Jmod files are carried in each JDK for out-of-the-box Jlink usage;
In addition to this, the Jmod files for each platform JDK are packaged and distributed separately, allowing users to download them individually.

Based on existing work on Fallback Jmod and SapMachine, I created a Release showing how I expected the distribution to be,
You can see my JDK distribution for Windows x86-64 and Linux x86-64 platforms [here](https://github.com/Glavo/fallback-jmod/releases/tag/jdk-1.0).

(Another idea: put the full Jmod files on the Maven repository)

## Known Issues

**The first thing to note is that this is just a prototype implementation, it's not production ready.**

The first problem is that some providers may modify the executables and libraries in the JDK, making them different from those in Jmod.
Fallback Jmod still works in this case, and the reduction and restore of the Jmod file is guaranteed to be lossless.
However, these files cannot be deleted at this time, so the size will increase by 20~30MB compared to the normal situation.
Of course, even in this case, the size of Jmod files is reduced by more than half.

Vendors that are known to do this are BellSoft and Azul, and those that don't are SAP.
Therefore, if you want to try this tool, it is recommended to use the [SapMachine](https://sap.github.io/SapMachine/) to achieve a more significant effect.
I'll continue to investigate this to find out exactly why they do this.

Of course, you can add the command line parameter `--include-without-verify /bin/**:/lib/**` to skip the verification of these different files to achieve a similar effect.


The second problem is that the Jlink mode of this tool is very limited. This is not a technical problem, but an implementation problem.
In theory, as a Jlink plugin, it can work completely transparently, and users usually don't need to perceive it.
However, the implementation of Jlink restricts this. By default, only built-in plug-ins are loaded, and it is difficult to hack this process.
So I used another way to call Jlink, for which I had to deal with the command line parameters myself.
Since this is just an implementation prototype, I don't want to make it too complicated, 
so I only implement the basic features for demonstration purposes.


Another problem is that due to the way `ModulePath` works, this tool will destroy the verification of the module hash value during the Jlink process.
It may be necessary to modify the JDK to make Fallback Jmod work before the hash check to fix the problem.
Before that, I had the tool remove the hash recorded in the module in reduction process so that the prototype would work.
(It seems that I can solve this problem by implementing `ModuleFinder` myself. Maybe it's better than implementing the Jlink plugin)


In addition, there seems to be some problems with the zip implementation of JDK.
The zip file created by this tool (use `ZipOutputStream` internally) may not be read by JDK,
but other software can recognize it normally.
If you encounter problems, please let me know by open a issue. 
I try to use other compression libraries instead of JDK.

## Future works

### Better support for Fallback Jmod within the JDK

Currently, I let the JDK recognize it through the Jlink plugin.

Honestly, it's not a good choice. The reason is, it should be part of the Jmod format, not something specific to Jlink.
I chose this because I wanted this little tool that works independently to have better integration with the JDK.

At present, I have implemented a `FallbackJmodModuleFinder` that can recognize fallback jmod, but to further integrate it with Jlink, I need to modify the source code of Jlink itself.
So for now I'm not going to provide it, in the future I'll try to fork the OpenJDK to implement it.

### Go one step further?

`fallback.list` is just an implementation specific detail of the first prototype.
Its format is very simple, only a list of hash values corresponding to paths is recorded.

In the future, I may introduce a richer Jmod manifest file to explore more possibilities of Jmod.

This is the list of current possible plans:

* Use a different extension to differentiate it from the full Jmod to avoid confusion.
* Record the hashes of files that have not been removed, which is used for verification during Jlink.
* Record some extra information, make the Jmod file can be used at runtime.
* Record platform related information to generate multi-platform Jmod files.
* ...

### Integrated into OpenJDK?

This is what I developed this project for. After this exploration, if the JCP is willing to embrace such an evolution, 
I'd be happy to spend time implementing the results of the exploration on OpenJDK.