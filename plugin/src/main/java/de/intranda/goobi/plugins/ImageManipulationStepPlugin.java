package de.intranda.goobi.plugins;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * This file is part of a plugin for Goobi - a Workflow tool for the support of mass digitization.
 *
 * Visit the websites for more information.
 *          - https://goobi.io
 *          - https://www.intranda.com
 *          - https://github.com/intranda/goobi
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program; if not, write to the Free Software Foundation, Inc., 59
 * Temple Place, Suite 330, Boston, MA 02111-1307 USA
 *
 */

import java.util.HashMap;
import java.util.List;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.goobi.beans.Step;
import org.goobi.production.enums.LogType;
import org.goobi.production.enums.PluginGuiType;
import org.goobi.production.enums.PluginReturnValue;
import org.goobi.production.enums.PluginType;
import org.goobi.production.enums.StepReturnValue;
import org.goobi.production.plugin.interfaces.IStepPluginVersion2;

import de.sub.goobi.config.ConfigPlugins;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.StorageProvider;
import de.sub.goobi.helper.exceptions.DAOException;
import de.sub.goobi.helper.exceptions.SwapException;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;
import net.xeoh.plugins.base.annotations.PluginImplementation;

@PluginImplementation
@Log4j2
public class ImageManipulationStepPlugin implements IStepPluginVersion2 {

    @Getter
    private String title = "intranda_step_image_manipulation";
    @Getter
    private Step step;
    @Getter
    private String value;
    private String returnPath;
    private List<HierarchicalConfiguration> rules;

    @Override
    public void initialize(Step step, String returnPath) {
        this.returnPath = returnPath;
        this.step = step;

        // read parameters from correct block in configuration file
        SubnodeConfiguration myconfig = ConfigPlugins.getProjectAndStepConfig(title, step);
        value = myconfig.getString("value", "default value");
        rules = myconfig.configurationsAt("./rule");
    }

    @Override
    public PluginGuiType getPluginGuiType() {
        return PluginGuiType.NONE;
    }

    @Override
    public String getPagePath() {
        return "/uii/plugin_step_image_manipulation.xhtml";
    }

    @Override
    public PluginType getType() {
        return PluginType.Step;
    }

    @Override
    public String cancel() {
        return "/uii" + returnPath;
    }

    @Override
    public String finish() {
        return "/uii" + returnPath;
    }

    @Override
    public int getInterfaceVersion() {
        return 0;
    }

    @Override
    public HashMap<String, StepReturnValue> validate() {
        return null;
    }

    @Override
    public boolean execute() {
        PluginReturnValue ret = run();
        return ret != PluginReturnValue.ERROR;
    }

    @Override
    public PluginReturnValue run() {
        boolean successful = true;

        // run through all configured rules
        for (HierarchicalConfiguration config : rules) {
            List <String> command =  Arrays.asList(config.getString("./command").split("\\|"));
            List<String> imagefolders = Arrays.asList(config.getStringArray("./imagefolder"));
            List<String> exclude = Arrays.asList(config.getStringArray("./exclude"));
            List<String> excludeLast = Arrays.asList(config.getStringArray("./excludeLast"));
            List<String> excludeFirst = Arrays.asList(config.getStringArray("./excludeFirst"));

            // run through all defined image folders
            try {
                for (String f : imagefolders) {
                    Path folder = Paths.get(step.getProzess().getConfiguredImageFolder(f));
                    log.debug("====================================================================================");
                    log.debug("Folder: " + folder.toString());
                    log.debug("Command: " + command);
                    log.debug("exclude: " + exclude);
                    log.debug("excludeLast: " + excludeLast);
                    log.debug("excludeFirst: " + excludeFirst);
                    List<Path> files = StorageProvider.getInstance().listFiles(folder.toString());
                    List<Path> result = StorageProvider.getInstance().listFiles(folder.toString());

                    // remove all odd or even files based on configuration
                    for (String ex : exclude) {
                        for (Path file : getSelectedFiles(files, ex, 0)) {
                            result.remove(file);
                        }
                    }
                    
                    // remove configured first files
                    for (String ex : excludeFirst) {
                        for (Path file : getSelectedFiles(files, "first", Integer.parseInt(ex))) {
                            result.remove(file);
                        }
                    }

                    // remove configured last files
                    for (String ex : excludeLast) {
                        for (Path file : getSelectedFiles(files, "last", Integer.parseInt(ex))) {
                            result.remove(file);
                        }
                    }

                    // now run through all found images and do the shell command call to manipulate the image
                    for (Path file : result) {
                        // log.debug("Result: " + file);
                        callScript(file.toString(), command);
                    }

                }
            } catch (IOException | InterruptedException | SwapException | DAOException e) {
                successful = false;
                log.error("Error during image manipulation", e);
                Helper.addMessageToProcessLog(step.getProzess().getId(), LogType.ERROR,
                        "Error during image manipulation: " + e.getMessage());
            }
        }

        if (!successful) {
            return PluginReturnValue.ERROR;
        }
        return PluginReturnValue.FINISH;
    }

    /**
     * Get a list of files of a given file list which are defined (e.g. just odd files, just the second to last file)
     * @param files     List of files to be run through
     * @param filter    Defines which files are searched (e.g. odd, even, last, first)
     * @param num       Used in case of first or last files to define which image (e.g. '2' as second to last)
     * @return          List of files which match the requirements
     */
    public static List<Path> getSelectedFiles(List<Path> files, String filter, int num) {
        List<Path> result = new ArrayList<Path>();
        for (int i = 0; i < files.size(); i++) {
            boolean even = i % 2 != 0;
            boolean odd = i % 2 == 0;
            int last = files.size() - i;
            int first = i + 1;
            // log.debug("File: " + files.get(i) + " - " + even + " - " + odd + " - " + last + " - " + first);

            if (filter.equals("odd") && odd) {
                result.add(files.get(i));
            } else if (filter.equals("even") && even) {
                result.add(files.get(i));
            } else if (filter.equals("last") && num == last) {
                result.add(files.get(i));
            } else if (filter.equals("first") && num == first) {
                result.add(files.get(i));
            }
        }
        return result;
    }
    
    /**
     * Calls a script to do a image manipulation (e.g. a rotation of an image)
     * 
     * @param imagePath Path of the image file to be manipulated
     * @param commandParameter  List of parameters to combine into a shell call
     * @throws IOException 
     * @throws InterruptedException 
     */
    private static void callScript(String imagePath, List<String> commandParameter) throws IOException, InterruptedException {
        List<String> commandLine = new ArrayList<>();
        for (String parameter : commandParameter) {
            commandLine.add(parameter.replace("IMAGE_FILE", imagePath));
        }
        log.debug(commandLine);
        String[] callSequence = commandLine.toArray(new String[commandLine.size()]);
        ProcessBuilder pb = new ProcessBuilder(callSequence);
        Process process = pb.start();
        int result = process.waitFor();
        if (result != 0) {
            log.error("A problem occured while calling command '" + commandLine + "'. The error code was " + result);
            Helper.setFehlerMeldung("A problem occured while calling command '" + commandLine + "'. The error code was " + result);
        }
    }

}
